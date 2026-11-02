package example.proxy;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Общий таймаут срабатывает при первой записи до фиксации ответа; ветка ошибки не должна перехватить его. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "proxy.max-in-flight=1", "proxy.exchange-timeout=1500ms", "proxy.read-timeout=5s"
})
@Import(ResponseOwnershipRaceTest.WriterBarrierConfiguration.class)
class ResponseOwnershipRaceTest {
    private static final CountDownLatch WRITE_ENTERED = new CountDownLatch(1);
    private static final CountDownLatch RELEASE_WRITE = new CountDownLatch(1);
    private static final List<Integer> STATUSES = new CopyOnWriteArrayList<>();
    private static final AtomicInteger RESETS = new AtomicInteger();
    private static final AtomicBoolean COMMITTED_AT_BARRIER = new AtomicBoolean();
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final HttpServer BACKEND = backend();
    @Value("${local.server.port}") int port;
    @Autowired ProxyTransport transport;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("proxy.backend-url", () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
    }

    @AfterAll static void stopBackend() {
        RELEASE_WRITE.countDown();
        BACKEND.stop(0);
        WORKERS.shutdownNow();
    }

    @AfterEach void stopRealResources() {
        RELEASE_WRITE.countDown();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(transport.inFlight.availablePermits()).isEqualTo(1);
            assertThat(transport.activeChannels()).isZero();
            assertThat(transport.activeIoTasks()).isZero();
            assertThat(transport.activeExchanges()).isZero();
        });
    }

    @Test void timeoutWhileFirstWriteIsBlockedAbortsWithoutCompetingFallback() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/race"))
                    .timeout(Duration.ofSeconds(8)).POST(HttpRequest.BodyPublishers.ofString("request-body")).build();
            var result = client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray());
            try {
                assertThat(WRITE_ENTERED.await(5, TimeUnit.SECONDS)).as("backend reached first servlet write").isTrue();
                assertThat(COMMITTED_AT_BARRIER).isFalse();
                // По таймауту соединение должно оборваться, пока владелец ответа ещё занят записью.
                // Конкурирующая ветка ошибки вместо этого вернула бы отдельный ответ 504.
                try {
                    var completed = result.get(4, TimeUnit.SECONDS);
                    throw new AssertionError("Unexpected completed response status=" + completed.statusCode()
                            + " body=" + new String(completed.body(), StandardCharsets.UTF_8)
                            + " observed statuses=" + STATUSES + " resets=" + RESETS.get()
                            + " activeIo=" + transport.activeIoTasks() + " permits=" + transport.inFlight.availablePermits());
                } catch (ExecutionException failure) {
                    assertThat(failure.getCause()).isInstanceOf(IOException.class).isNotInstanceOf(HttpTimeoutException.class);
                }
                assertThat(STATUSES).containsExactly(201);
                assertThat(RESETS).hasValue(0);
                // Прерывание потока ещё не означает завершения задачи: тестовая обёртка
                // удерживает запись до явного снятия барьера, поэтому слот пока занят.
                assertThat(transport.activeIoTasks()).isPositive();
                assertThat(transport.inFlight.availablePermits()).isZero();
            } finally {
                RELEASE_WRITE.countDown();
            }
        }
    }

    private static HttpServer backend() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/race", exchange -> {
                try (exchange) {
                    exchange.getRequestBody().readAllBytes(); // Здесь читается только небольшое фиксированное тело тестового запроса.
                    byte[] body = "backend-response".getBytes(StandardCharsets.US_ASCII);
                    exchange.sendResponseHeaders(201, body.length);
                    exchange.getResponseBody().write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class WriterBarrierConfiguration {
        @Bean FilterRegistrationBean<Filter> firstWriteBarrier() {
            Filter filter = (request, response, chain) -> chain.doFilter(request,
                    new HttpServletResponseWrapper((HttpServletResponse) response) {
                        private ServletOutputStream stream;
                        @Override public void setStatus(int status) {
                            STATUSES.add(status);
                            super.setStatus(status);
                        }
                        @Override public void reset() {
                            RESETS.incrementAndGet();
                            super.reset();
                        }
                        @Override public ServletOutputStream getOutputStream() throws IOException {
                            if (stream == null) {
                                ServletOutputStream output = super.getOutputStream();
                                AtomicBoolean first = new AtomicBoolean(true);
                                stream = new ServletOutputStream() {
                                    private void barrier() throws IOException {
                                        if (!first.compareAndSet(true, false)) return;
                                        COMMITTED_AT_BARRIER.set(isCommitted());
                                        WRITE_ENTERED.countDown();
                                        boolean interrupted = false;
                                        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
                                        try {
                                            while (RELEASE_WRITE.getCount() != 0) {
                                                long remaining = end - System.nanoTime();
                                                if (remaining <= 0) throw new IOException("Test writer barrier was not released");
                                                try {
                                                    if (!RELEASE_WRITE.await(remaining, TimeUnit.NANOSECONDS)) {
                                                        throw new IOException("Test writer barrier timed out");
                                                    }
                                                } catch (InterruptedException expectedCancellation) {
                                                    // Намеренно не выходим по interrupt: проверяем завершение
                                                    // самой задачи, а не только сигнал отмены или изменение счётчика.
                                                    interrupted = true;
                                                }
                                            }
                                        } finally {
                                            if (interrupted) Thread.currentThread().interrupt();
                                        }
                                    }
                                    @Override public void write(int value) throws IOException { barrier(); output.write(value); }
                                    @Override public void write(byte[] bytes, int offset, int count) throws IOException {
                                        barrier(); output.write(bytes, offset, count);
                                    }
                                    @Override public void flush() throws IOException { output.flush(); }
                                    @Override public boolean isReady() { return output.isReady(); }
                                    @Override public void setWriteListener(WriteListener listener) { output.setWriteListener(listener); }
                                };
                            }
                            return stream;
                        }
                    });
            var registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns("/race");
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setAsyncSupported(true);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            return registration;
        }
    }
}
