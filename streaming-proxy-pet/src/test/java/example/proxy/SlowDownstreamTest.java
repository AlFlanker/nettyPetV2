package example.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Проверяет обратное давление от медленного клиента и освобождение ресурсов после TCP RST. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"proxy.max-in-flight=2", "proxy.exchange-timeout=20s"})
class SlowDownstreamTest {
    private static final long TOTAL_BYTES = 64L * 1024 * 1024;
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final AtomicLong WRITTEN = new AtomicLong();
    private static final AtomicBoolean SENT_ALL = new AtomicBoolean();
    private static final CountDownLatch WRITER_EXITED = new CountDownLatch(1);
    private static final HttpServer BACKEND = startBackend();
    @Autowired ProxyTransport transport;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource static void backendUrl(DynamicPropertyRegistry registry) {
        registry.add("proxy.backend-url", () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
    }

    @AfterEach void resourcesAreActuallyStopped() {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(transport.activeIoTasks()).isZero();
            assertThat(transport.activeChannels()).isZero();
            assertThat(transport.activeExchanges()).isZero();
            assertThat(transport.inFlight.availablePermits()).isEqualTo(2);
        });
    }

    @AfterAll static void stopBackend() {
        BACKEND.stop(0);
        WORKERS.shutdownNow();
    }

    @Test void pausedFrontendStopsBackendProgressAndRstReleasesTheExchange() throws Exception {
        try (Socket frontend = new Socket()) {
            frontend.setReceiveBufferSize(1024); // Задаём до connect, чтобы TCP согласовал небольшое окно приёма.
            frontend.setSoTimeout(5000);
            frontend.setSoLinger(true, 0);
            frontend.connect(new InetSocketAddress("127.0.0.1", port));
            frontend.getOutputStream().write(("GET /large HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            frontend.getOutputStream().flush();
            String head = readHead(frontend);
            assertThat(head).startsWith("HTTP/1.1 200");
            assertThat(head.toLowerCase(java.util.Locale.ROOT)).contains("content-length: " + TOTAL_BYTES);
            byte[] prefix = frontend.getInputStream().readNBytes(1024);
            assertThat(prefix).hasSize(1024);
            for (int i = 0; i < prefix.length; i++) assertThat(prefix[i]).isEqualTo((byte) i);

            // Клиент больше не читает. Ждём, пока бэкенд перестанет продвигаться:
            // ограниченная очередь прокси не исключает буферы Jetty, Netty и обоих TCP-соединений.
            var previous = new AtomicLong(-1);
            var stablePolls = new AtomicInteger();
            org.awaitility.Awaitility.await().pollInterval(Duration.ofMillis(75))
                    .atMost(Duration.ofSeconds(5)).until(() -> {
                        long now = WRITTEN.get();
                        if (previous.getAndSet(now) == now) stablePolls.incrementAndGet();
                        else stablePolls.set(0);
                        return stablePolls.get() >= 5;
                    });
            assertThat(WRITTEN.get()).isPositive().isLessThan(32L * 1024 * 1024);
            assertThat(SENT_ALL).isFalse();
            assertThat(transport.activeExchanges()).isEqualTo(1);
            assertThat(transport.activeIoTasks()).isPositive();
            // TCP RST должен разблокировать запись прокси и остановить производителя на бэкенде.
        }
        assertThat(WRITER_EXITED.await(5, TimeUnit.SECONDS)).isTrue();
        resourcesAreActuallyStopped();
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/healthy"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("still works");
        }
    }

    private static String readHead(Socket socket) throws IOException {
        var head = new ByteArrayOutputStream();
        int matched = 0;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        while (matched != terminator.length) {
            int next = socket.getInputStream().read();
            if (next < 0 || head.size() > 16 * 1024) throw new IOException("Missing response head");
            head.write(next);
            matched = next == terminator[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
        }
        return head.toString(StandardCharsets.US_ASCII);
    }

    private static HttpServer startBackend() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/", SlowDownstreamTest::respond);
            server.start();
            return server;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        if (exchange.getRequestURI().getPath().equals("/healthy")) {
            try (exchange) {
                byte[] body = "still works".getBytes(StandardCharsets.US_ASCII);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            return;
        }
        try (exchange) {
            byte[] chunk = new byte[16 * 1024];
            for (int i = 0; i < chunk.length; i++) chunk[i] = (byte) i;
            exchange.sendResponseHeaders(200, TOTAL_BYTES);
            for (long offset = 0; offset < TOTAL_BYTES; offset += chunk.length) {
                exchange.getResponseBody().write(chunk);
                WRITTEN.addAndGet(chunk.length);
            }
            SENT_ALL.set(true);
        } catch (IOException expectedDownstreamAbort) {
            // Бэкенд не хранит тело целиком; обрыв клиента должен остановить именно его запись.
        } finally {
            WRITER_EXITED.countDown();
        }
    }
}
