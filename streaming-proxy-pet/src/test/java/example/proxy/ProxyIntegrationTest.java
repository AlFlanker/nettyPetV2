package example.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.zip.GZIPOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Проверяет передачу исходных байтов и HTTP-семантику через настоящий Jetty-прокси.
 * Управляемые задержки бэкенда позволяют отличить потоковую передачу от скрытой буферизации.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProxyIntegrationTest {
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer BACKEND = startBackend();
    private static final AtomicReference<SeenRequest> SEEN = new AtomicReference<>();
    private static volatile CountDownLatch uploadReceived;
    private static volatile CountDownLatch responseConsumed;
    private static volatile CountDownLatch abortedUploadReceived;
    private static volatile CountDownLatch pressureReadStarted;
    private static volatile CountDownLatch releasePressureReads;
    private static final byte[] RESPONSE_PREFIX = "first-chunk\n".getBytes(StandardCharsets.UTF_8);
    @Value("${local.server.port}") int port;
    @Autowired ProxyTransport transport;
    @Value("${proxy.max-in-flight:32}") int capacity;
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    private static final AtomicReference<Map<String, List<String>>> SEEN_HEADERS = new AtomicReference<>();
    private static final AtomicReference<String> SEEN_DIGEST = new AtomicReference<>();

    @DynamicPropertySource
    static void backendUrl(DynamicPropertyRegistry registry) {
        registry.add("proxy.backend-url", () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
    }

    @AfterAll static void stopBackend() {
        BACKEND.stop(0);
        WORKERS.shutdownNow();
    }

    @AfterEach void everyExchangeReleasesItsConcurrencyPermit() {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(transport.inFlight.availablePermits()).isEqualTo(capacity);
            assertThat(transport.activeIoTasks()).isZero();
            assertThat(transport.activeChannels()).isZero();
            assertThat(transport.activeExchanges()).isZero();
        });
    }

    @Test void formPostPreservesExactBytesQueryAndRepeatedParameters() throws Exception {
        byte[] body = "a=one+two&a=one%20two&empty=&encoded=%2f%2F&bare".getBytes(StandardCharsets.US_ASCII);
        try (HttpClient client = client()) {
            var response = client.send(request("/echo?a=query&a=other%20value&bare")
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.body()).isEqualTo(body);
            assertThat(SEEN.get().query()).isEqualTo("a=query&a=other%20value&bare");
            assertThat(SEEN.get().method()).isEqualTo("POST");
        }
    }

    @Test void multipartPreservesBoundaryAndBinaryFileBytes() throws Exception {
        String contentType = "multipart/form-data; boundary=UnchangedBoundary";
        byte[] body = ("--UnchangedBoundary\r\nContent-Disposition: form-data; name=\"field\"\r\n\r\none\r\n"
                + "--UnchangedBoundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"data.bin\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n\u0000\u00ff\u0080binary\r\n--UnchangedBoundary--\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);
        try (HttpClient client = client()) {
            var response = client.send(request("/echo").header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.body()).isEqualTo(body);
            assertThat(SEEN.get().contentType()).isEqualTo(contentType);
        }
    }

    @Test void putAndPatchPreserveArbitraryBinaryBodiesAndResponseMetadata() throws Exception {
        byte[] body = new byte[8193];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;
        try (HttpClient client = client()) {
            for (String method : List.of("PUT", "PATCH")) {
                var response = client.send(request("/echo").header("Content-Type", "application/octet-stream")
                        .method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
                assertThat(response.statusCode()).isEqualTo(201);
                assertThat(response.body()).isEqualTo(body);
                assertThat(SEEN.get().method()).isEqualTo(method);
                assertThat(response.headers().allValues("Set-Cookie")).containsExactlyInAnyOrder("a=1; Path=/", "b=2; Path=/");
                assertThat(response.headers().firstValue("X-Backend")).contains("yes");
                assertThat(response.headers().firstValue("X-Hop-Only")).isEmpty();
                assertThat(response.headers().firstValue("Keep-Alive")).isEmpty();
            }
        }
    }

    @Test void largeChunkedUploadReachesBackendBeforeProducerCanFinish() throws Exception {
        // Источник не отдаёт остаток тела, пока бэкенд не получит первые байты.
        // При полной буферизации обе стороны останутся ждать и тест завершится по таймауту.
        uploadReceived = new CountDownLatch(1);
        int size = 16 * 1024 * 1024;
        AtomicBoolean reachedEof = new AtomicBoolean();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (HttpClient client = client()) {
            InputStream producer = new InputStream() {
                int position;
                boolean gated;
                @Override public int read() throws IOException {
                    byte[] one = new byte[1];
                    return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    if (length == 0) return 0;
                    if (!gated && position >= 64 * 1024) {
                        gated = true;
                        await(uploadReceived, "backend never received an upload chunk before EOF");
                        assertThat(reachedEof).isFalse();
                    }
                    if (position == size) { reachedEof.set(true); return -1; }
                    int count = Math.min(length, size - position);
                    for (int i = 0; i < count; i++) buffer[offset + i] = (byte) (position++ * 31 + 17);
                    digest.update(buffer, offset, count);
                    return count;
                }
            };
            var response = client.send(request("/digest").header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofInputStream(() -> producer)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(reachedEof).isTrue();
            assertThat(response.body()).isEqualTo(size + ":" + HexFormat.of().formatHex(digest.digest()));
        }
    }

    @Test void stalledBackendBoundsUploadReadAheadAndResumesWithExactDigest() throws Exception {
        pressureReadStarted = new CountDownLatch(1);
        releasePressureReads = new CountDownLatch(1);
        long size = 64L * 1024 * 1024;
        var produced = new java.util.concurrent.atomic.AtomicLong();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream source = new InputStream() {
            long position;
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (length == 0) return 0;
                if (position == size) return -1;
                int count = (int) Math.min(length, size - position);
                for (int i = 0; i < count; i++) bytes[offset + i] = (byte) (position++ * 13 + 7);
                digest.update(bytes, offset, count);
                produced.addAndGet(count);
                return count;
            }
        };
        try (HttpClient client = client()) {
            var response = client.sendAsync(request("/pressure-upload").POST(
                    HttpRequest.BodyPublishers.ofInputStream(() -> source)).build(), HttpResponse.BodyHandlers.ofString());
            try {
                assertThat(pressureReadStarted.await(5, TimeUnit.SECONDS)).isTrue();
                var previous = new java.util.concurrent.atomic.AtomicLong(-1);
                var stable = new java.util.concurrent.atomic.AtomicInteger();
                // Проверяем остановку чтения источника; точного лимита в один чанк здесь нет:
                // данные могут находиться в буферах обоих TCP-соединений.
                org.awaitility.Awaitility.await().pollInterval(Duration.ofMillis(75))
                        .atMost(Duration.ofSeconds(5)).until(() -> {
                            long now = produced.get();
                            if (previous.getAndSet(now) == now) stable.incrementAndGet();
                            else stable.set(0);
                            return stable.get() >= 5;
                        });
                assertThat(produced.get()).isPositive().isLessThan(32L * 1024 * 1024);
                assertThat(response).isNotDone();
                releasePressureReads.countDown();
                var result = response.get(15, TimeUnit.SECONDS);
                assertThat(result.statusCode()).isEqualTo(200);
                assertThat(result.body()).isEqualTo(size + ":" + HexFormat.of().formatHex(digest.digest()));
            } finally {
                releasePressureReads.countDown();
                if (!response.isDone()) response.cancel(true);
            }
        }
    }

    @Test void responseFirstChunkArrivesBeforeBackendFinishes() throws Exception {
        responseConsumed = new CountDownLatch(1);
        try (HttpClient client = client()) {
            var response = client.send(request("/stream-response").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                assertThat(body.readNBytes(RESPONSE_PREFIX.length)).isEqualTo(RESPONSE_PREFIX);
                responseConsumed.countDown();
                assertThat(new String(body.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("last-chunk\n");
            }
        } finally { responseConsumed.countDown(); }
    }

    @Test void abortedClientUploadReleasesCapacityAndNextRequestSucceeds() throws Exception {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(transport.inFlight.availablePermits()).isEqualTo(capacity);
            assertThat(transport.activeIoTasks()).isZero();
            assertThat(transport.activeChannels()).isZero();
            assertThat(transport.activeExchanges()).isZero();
        });
        abortedUploadReceived = new CountDownLatch(1);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoLinger(true, 0);
            socket.getOutputStream().write(("POST /abort-upload HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 100000000\r\nContent-Type: application/octet-stream\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(new byte[64 * 1024]);
            socket.getOutputStream().flush();
            assertThat(abortedUploadReceived.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(transport.inFlight.availablePermits()).isEqualTo(capacity - 1);
            // Клиент обрывает соединение, не отправив большую часть заявленного тела:
            // прокси должен отменить обмен и освободить его ресурсы.
        }
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(transport.inFlight.availablePermits()).isEqualTo(capacity);
            assertThat(transport.activeIoTasks()).isZero();
            assertThat(transport.activeChannels()).isZero();
            assertThat(transport.activeExchanges()).isZero();
        });
        try (HttpClient client = client()) {
            var response = client.send(request("/echo").POST(HttpRequest.BodyPublishers.ofString("still works")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.body()).isEqualTo("still works");
        }
    }

    @Test void backendDisconnectBeforeHeadersReturns502() throws Exception {
        try (HttpClient client = client()) {
            var response = client.send(request("/disconnect").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(502);
        }
    }

    @Test void truncatedBackendResponseIsNotReportedAsSuccessfulCompleteBody() throws Exception {
        try (HttpClient client = client()) {
            long started = System.nanoTime();
            var response = client.send(request("/truncate").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (InputStream body = response.body()) {
                assertThat(body.readNBytes(RESPONSE_PREFIX.length)).isEqualTo(RESPONSE_PREFIX);
                IOException error = assertThrows(IOException.class, body::readAllBytes);
                assertThat(error).isNotInstanceOf(HttpTimeoutException.class);
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test void getAndDeleteStreamWithKnownAndUnknownLength() throws Exception {
        for (String method : List.of("GET", "DELETE")) {
            for (boolean knownLength : List.of(true, false)) {
                assertStreamingRequest(method, knownLength);
            }
        }
    }

    @Test void headRequestBodyStreamsButHeadResponseHasNoBody() throws Exception {
        assertStreamingRequest("HEAD", true);
        assertStreamingRequest("HEAD", false);
    }

    private void assertStreamingRequest(String method, boolean knownLength) throws Exception {
        uploadReceived = new CountDownLatch(1);
        SEEN_DIGEST.set(null);
        int size = 256 * 1024;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream producer = new InputStream() {
            int position;
            boolean gatePassed;
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) return 0;
                if (!gatePassed && position >= 64 * 1024) {
                    gatePassed = true;
                    await(uploadReceived, method + " was buffered before the backend received its first chunk");
                }
                if (position == size) return -1;
                int count = Math.min(length, size - position);
                for (int i = 0; i < count; i++) buffer[offset + i] = (byte) (position++ * 17 + 11);
                digest.update(buffer, offset, count);
                return count;
            }
        };
        HttpRequest.BodyPublisher source = HttpRequest.BodyPublishers.ofInputStream(() -> producer);
        HttpRequest.BodyPublisher publisher = knownLength
                ? HttpRequest.BodyPublishers.fromPublisher(source, size) : source;
        try (HttpClient client = client()) {
            var response = client.send(request("/digest").method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(method + " knownLength=" + knownLength).isEqualTo(200);
            String expected = size + ":" + HexFormat.of().formatHex(digest.digest());
            assertThat(SEEN_DIGEST.get()).isEqualTo(expected);
            assertThat(response.body()).isEqualTo(method.equals("HEAD") ? "" : expected);
            Map<String, List<String>> headers = SEEN_HEADERS.get();
            assertThat(headers.containsKey("content-length") && headers.containsKey("transfer-encoding")).isFalse();
            if (knownLength) assertThat(headers.get("content-length")).containsExactly(Integer.toString(size));
            else assertThat(headers.get("transfer-encoding")).containsExactly("chunked");
        }
    }

    @Test void head204And304CompleteWithoutResponseBody() throws Exception {
        try (HttpClient client = client()) {
            for (int status : List.of(204, 304)) {
                var response = client.send(request("/empty/" + status).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                assertThat(response.statusCode()).isEqualTo(status);
                assertThat(response.body()).isEmpty();
            }
            var response = client.send(request("/empty/200").method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEmpty();
        }
    }

    @Test void compressedPayloadIsForwardedWithoutDecompression() throws Exception {
        byte[] gzip = gzipPayload();
        try (HttpClient client = client()) {
            var upload = client.send(request("/echo").header("Content-Encoding", "gzip")
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(gzip)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(upload.body()).isEqualTo(gzip);
            assertThat(SEEN_HEADERS.get().get("content-encoding")).containsExactly("gzip");
            var download = client.send(request("/gzip").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(download.headers().firstValue("Content-Encoding")).contains("gzip");
            assertThat(download.body()).isEqualTo(gzip);
        }
    }

    @Test void requestHopByHopHeadersAreRemovedAndRepeatedEndToEndHeadersSurvive() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /echo HTTP/1.1\r\nHost: misleading.example\r\n"
                    + "Connection: close, X-Remove\r\nX-Remove: secret\r\n"
                    + "Keep-Alive: timeout=5\r\nX-Multi: first\r\nX-Multi: second\r\n"
                    + "Authorization: Bearer test-only\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(response).startsWith("HTTP/1.1 201");
            Map<String, List<String>> headers = SEEN_HEADERS.get();
            assertThat(headers).doesNotContainKeys("x-remove", "keep-alive");
            assertThat(headers.get("x-multi")).containsExactly("first", "second");
            assertThat(headers.get("authorization")).containsExactly("Bearer test-only");
            assertThat(headers.get("host")).containsExactly("127.0.0.1:" + BACKEND.getAddress().getPort());
        }
    }

    @Test void encodedPathAndQueryAreNotDecodedAndReencoded() throws Exception {
        String target = "/path/a%20b?x=%2B&x=+&empty=";
        try (HttpClient client = client()) {
            var response = client.send(request(target).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo(target);
        }
    }

    @Test void optionsPreservesMethodAllowStatusAndOptionalBody() throws Exception {
        try (HttpClient client = client()) {
            var empty = client.send(request("/options").method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(empty.statusCode()).isEqualTo(204);
            assertThat(empty.headers().firstValue("Allow")).contains("GET, HEAD, POST, OPTIONS");
            assertThat(empty.headers().firstValue("X-Options-Backend")).contains("yes");
            assertThat(empty.body()).isEmpty();
            assertThat(SEEN.get().method()).isEqualTo("OPTIONS");

            byte[] payload = "capability-query=raw%20bytes".getBytes(StandardCharsets.US_ASCII);
            var withBody = client.send(request("/options").header("Content-Type", "application/octet-stream")
                    .method("OPTIONS", HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            assertThat(withBody.statusCode()).isEqualTo(200);
            assertThat(withBody.headers().firstValue("Allow")).contains("GET, HEAD, POST, OPTIONS");
            assertThat(withBody.body()).isEqualTo(payload);
            assertThat(SEEN.get().method()).isEqualTo("OPTIONS");
        }
    }

    @Test void expectContinueAndPostWithoutCharsetPreserveBody() throws Exception {
        byte[] body = "x=%FF&x=a+b".getBytes(StandardCharsets.US_ASCII);
        try (HttpClient client = client()) {
            var response = client.send(request("/echo").expectContinue(true)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(response.statusCode()).isEqualTo(201);
            assertThat(response.body()).isEqualTo(body);
            assertThat(SEEN_HEADERS.get()).doesNotContainKey("expect");
        }
    }

    @Test void runtimeUsesJettyAndHasNoReactorNettyOrTomcatServer() {
        assertThat(applicationContext.getBeansOfType(
                org.springframework.boot.jetty.servlet.JettyServletWebServerFactory.class)).hasSize(1);
        assertThrows(ClassNotFoundException.class, () -> Class.forName("reactor.netty.http.client.HttpClient"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("org.apache.catalina.startup.Tomcat"));
    }

    private static byte[] gzipPayload() throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) {
            gzip.write("original gzip bytes: \u0000 payload".getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25));
    }
    private static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    }
    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/", ProxyIntegrationTest::handleBackend);
            server.start();
            return server;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private static void handleBackend(HttpExchange exchange) throws IOException {
        try (exchange) {
            var capturedHeaders = new LinkedHashMap<String, List<String>>();
            exchange.getRequestHeaders().forEach((name, values) -> capturedHeaders.put(name.toLowerCase(java.util.Locale.ROOT), List.copyOf(values)));
            SEEN_HEADERS.set(capturedHeaders);
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/empty/")) {
                int status = Integer.parseInt(path.substring("/empty/".length()));
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            if (path.startsWith("/path/")) {
                byte[] body = exchange.getRequestURI().toASCIIString().getBytes(StandardCharsets.US_ASCII);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                return;
            }
            switch (exchange.getRequestURI().getPath()) {
                case "/echo" -> {
                    SEEN.set(new SeenRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery(),
                            exchange.getRequestHeaders().getFirst("Content-Type")));
                    byte[] body = exchange.getRequestBody().readAllBytes(); // Целиком читаются только небольшие тестовые данные; /digest обрабатывается порциями.
                    exchange.getResponseHeaders().add("Set-Cookie", "a=1; Path=/");
                    exchange.getResponseHeaders().add("Set-Cookie", "b=2; Path=/");
                    exchange.getResponseHeaders().add("X-Backend", "yes");
                    exchange.getResponseHeaders().add("Connection", "X-Hop-Only, keep-alive");
                    exchange.getResponseHeaders().add("X-Hop-Only", "must-not-leak");
                    exchange.getResponseHeaders().add("Keep-Alive", "timeout=5");
                    exchange.sendResponseHeaders(201, body.length);
                    exchange.getResponseBody().write(body);
                }
                case "/digest" -> {
                    try {
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        byte[] buffer = new byte[8192];
                        long count = 0;
                        int read;
                        while ((read = exchange.getRequestBody().read(buffer)) != -1) {
                            if (read > 0) uploadReceived.countDown();
                            digest.update(buffer, 0, read);
                            count += read;
                        }
                        String result = count + ":" + HexFormat.of().formatHex(digest.digest());
                        SEEN_DIGEST.set(result);
                        byte[] answer = result.getBytes(StandardCharsets.US_ASCII);
                        if (exchange.getRequestMethod().equals("HEAD")) exchange.sendResponseHeaders(200, -1);
                        else {
                            exchange.sendResponseHeaders(200, answer.length);
                            exchange.getResponseBody().write(answer);
                        }
                    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
                }
                case "/pressure-upload" -> {
                    try {
                        MessageDigest digest = MessageDigest.getInstance("SHA-256");
                        byte[] chunk = new byte[8192];
                        long count = 0;
                        for (int read; (read = exchange.getRequestBody().read(chunk)) != -1;) {
                            digest.update(chunk, 0, read);
                            count += read;
                            if (pressureReadStarted.getCount() != 0) {
                                pressureReadStarted.countDown();
                                await(releasePressureReads, "test did not release stalled backend");
                            }
                        }
                        byte[] result = (count + ":" + HexFormat.of().formatHex(digest.digest())).getBytes(StandardCharsets.US_ASCII);
                        exchange.sendResponseHeaders(200, result.length);
                        exchange.getResponseBody().write(result);
                    } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
                }
                case "/stream-response" -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write(RESPONSE_PREFIX);
                    exchange.getResponseBody().flush();
                    await(responseConsumed, "client did not receive response prefix before backend EOF");
                    exchange.getResponseBody().write("last-chunk\n".getBytes(StandardCharsets.UTF_8));
                }
                case "/abort-upload" -> {
                    byte[] buffer = new byte[8192];
                    try {
                        while (exchange.getRequestBody().read(buffer) != -1) abortedUploadReceived.countDown();
                    } catch (IOException expectedClientAbort) { /* Тело запроса намеренно оборвано клиентом. */ }
                }
                case "/options" -> {
                    SEEN.set(new SeenRequest(exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery(),
                            exchange.getRequestHeaders().getFirst("Content-Type")));
                    byte[] body = exchange.getRequestBody().readAllBytes(); // Небольшое тело используется только для проверки OPTIONS.
                    exchange.getResponseHeaders().set("Allow", "GET, HEAD, POST, OPTIONS");
                    exchange.getResponseHeaders().set("X-Options-Backend", "yes");
                    if (body.length == 0) exchange.sendResponseHeaders(204, -1);
                    else {
                        exchange.sendResponseHeaders(200, body.length);
                        exchange.getResponseBody().write(body);
                    }
                }
                case "/gzip" -> {
                    byte[] body = gzipPayload();
                    exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                }
                case "/disconnect" -> { /* Закрываем соединение, не отправляя ответ. */ }
                case "/truncate" -> {
                    exchange.sendResponseHeaders(200, 1000);
                    exchange.getResponseBody().write(RESPONSE_PREFIX);
                    exchange.getResponseBody().flush();
                }
                default -> exchange.sendResponseHeaders(404, -1);
            }
        }
    }
    private static void await(CountDownLatch latch, String failure) throws IOException {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException(failure); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    private record SeenRequest(String method, String query, String contentType) { }
}
