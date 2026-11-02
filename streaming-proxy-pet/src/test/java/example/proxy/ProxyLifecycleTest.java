package example.proxy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Проверяет отказы и таймауты через реальные сокеты. Диагностический ERROR-фильтр
 * воспроизводит освобождение слота между отказом 503 и внутренней диспетчеризацией ошибки.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "proxy.max-in-flight=2", "proxy.exchange-timeout=2s", "proxy.read-timeout=10s"
})
@Import(ProxyLifecycleTest.ErrorProbe.class)
class ProxyLifecycleTest {
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer BACKEND = startBackend();
    private static volatile CountDownLatch releaseBackend;
    private static volatile CountDownLatch heldRequests;
    private static final AtomicInteger INTERNAL_ERRORS = new AtomicInteger();
    private static final AtomicInteger REJECTED_UPSTREAM = new AtomicInteger();
    private static final AtomicInteger UPSTREAM_REQUESTS = new AtomicInteger();
    private static final AtomicInteger ERROR_DISPATCHES = new AtomicInteger();
    @Value("${local.server.port}") int port;
    @Autowired ProxyTransport transport;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("proxy.backend-url", () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
    }

    @BeforeEach void prepare() {
        releaseBackend = new CountDownLatch(1);
        heldRequests = new CountDownLatch(2);
        INTERNAL_ERRORS.set(0);
        REJECTED_UPSTREAM.set(0);
        UPSTREAM_REQUESTS.set(0);
        ERROR_DISPATCHES.set(0);
    }

    @AfterEach void actualResourcesTerminate() {
        releaseBackend.countDown();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(transport.inFlight.availablePermits()).isEqualTo(2);
            assertThat(transport.activeIoTasks()).isZero();
            assertThat(transport.activeChannels()).isZero();
            assertThat(transport.activeExchanges()).isZero();
        });
    }

    @AfterAll static void shutdownBackend() {
        BACKEND.stop(0);
        WORKERS.shutdownNow();
    }

    @Test void rejectionStays503WhenCapacityFreesBeforeErrorDispatch() throws Exception {
        try (HttpClient client = client()) {
            var first = client.sendAsync(request("/hold").GET().build(), HttpResponse.BodyHandlers.ofString());
            var second = client.sendAsync(request("/hold").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(heldRequests.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(transport.inFlight.availablePermits()).isZero();
            try {
                var rejected = client.send(request("/rejected").GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(rejected.statusCode()).isEqualTo(503);
                assertThat(ERROR_DISPATCHES).hasValue(0);
                assertThat(INTERNAL_ERRORS).hasValue(0);
                assertThat(REJECTED_UPSTREAM).hasValue(0);
            } finally {
                releaseBackend.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(second.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
    }

    @Test void upgradeRejectionDoesNotReachBackendOrErrorDispatcher() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("GET /upgrade HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close, Upgrade\r\nUpgrade: websocket\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertThat(new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1))
                    .startsWith("HTTP/1.1 501");
            assertThat(UPSTREAM_REQUESTS).hasValue(0);
            assertThat(ERROR_DISPATCHES).hasValue(0);
        }
    }

    @Test void totalDeadlineBeforeResponseReturns504AndReleasesRealResources() throws Exception {
        try (HttpClient client = client()) {
            long started = System.nanoTime();
            var response = client.send(request("/timeout-before-head").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(504);
            assertThat(response.body()).doesNotContain("backend-data");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test void totalDeadlineAfterCommitAbortsInsteadOfAppendingFallback() throws Exception {
        try (HttpClient client = client()) {
            long started = System.nanoTime();
            var response = client.send(request("/timeout-after-prefix").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try (InputStream body = response.body()) {
                assertThat(new String(body.readNBytes(7), StandardCharsets.US_ASCII)).isEqualTo("prefix\n");
                IOException failure = assertThrows(IOException.class, body::readAllBytes);
                assertThat(failure).isNotInstanceOf(HttpTimeoutException.class);
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        }
    }

    @Test void early413StopsIncompleteUploadAndPreservesOriginStatus() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(("POST /early-reject HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 10000000\r\nContent-Type: application/octet-stream\r\n"
                    + "Connection: close\r\n\r\nfirst-chunk").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(response).startsWith("HTTP/1.1 413");
        }
    }

    @Test void redirectIsReturnedWithoutFollowingAndDisconnectIsNotRetried() throws Exception {
        try (HttpClient client = client()) {
            var redirect = client.send(request("/redirect").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(redirect.statusCode()).isEqualTo(307);
            assertThat(redirect.headers().firstValue("Location")).contains("/must-not-follow");
            assertThat(UPSTREAM_REQUESTS).hasValue(1);
            var failed = client.send(request("/disconnect").GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(failed.statusCode()).isEqualTo(502);
            assertThat(UPSTREAM_REQUESTS).hasValue(2);
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(8));
    }
    private static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    }
    private static HttpServer startBackend() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/", ProxyLifecycleTest::handle);
            server.start();
            return server;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    private static void handle(HttpExchange exchange) throws IOException {
        UPSTREAM_REQUESTS.incrementAndGet();
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/hold" -> {
                    heldRequests.countDown();
                    awaitRelease();
                    answer(exchange, 200, "held response");
                }
                case "/timeout-before-head" -> {
                    awaitRelease();
                    answer(exchange, 200, "backend-data");
                }
                case "/timeout-after-prefix" -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write("prefix\n".getBytes(StandardCharsets.US_ASCII));
                    exchange.getResponseBody().flush();
                    awaitRelease();
                    exchange.getResponseBody().write("backend-data".getBytes(StandardCharsets.US_ASCII));
                }
                case "/early-reject" -> exchange.sendResponseHeaders(413, -1);
                case "/error" -> { INTERNAL_ERRORS.incrementAndGet(); answer(exchange, 200, "backend:/error"); }
                case "/rejected" -> { REJECTED_UPSTREAM.incrementAndGet(); answer(exchange, 200, "must not arrive"); }
                case "/redirect" -> {
                    exchange.getResponseHeaders().set("Location", "/must-not-follow");
                    exchange.sendResponseHeaders(307, -1);
                }
                case "/disconnect" -> { }
                default -> answer(exchange, 200, "backend-data");
            }
        }
    }
    private static void awaitRelease() throws IOException {
        try {
            if (!releaseBackend.await(6, TimeUnit.SECONDS)) throw new IOException("Test release gate timed out");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.US_ASCII);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ErrorProbe {
        @Bean FilterRegistrationBean<Filter> releaseCapacityOnError(ProxyTransport transport) {
            // Если прокси ошибочно запустит диспетчеризацию ERROR, освобождаем реальные запросы
            // перед его продолжением. Отказ 503 всё равно не должен уйти на бэкенд как /error.
            Filter filter = (request, response, chain) -> {
                ERROR_DISPATCHES.incrementAndGet();
                releaseBackend.countDown();
                org.awaitility.Awaitility.await().atMost(Duration.ofMillis(800)).until(
                        () -> transport.inFlight.availablePermits() > 0);
                chain.doFilter(request, response);
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.setDispatcherTypes(DispatcherType.ERROR);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
