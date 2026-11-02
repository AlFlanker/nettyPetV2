package example.proxy;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Проверяет настоящее завершение AsyncContext: обрыв сокета и возврат слота сами по себе этого не доказывают. */
class ProxyServletCompletionTest {
    @Test void hardAbortCompletesActualServletAsyncAndContextClosesWithoutGraceTimeout() throws Exception {
        var probe = new CompletionProbe();
        var releaseBackend = new CountDownLatch(1);
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        var backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.setExecutor(workers);
        byte[] prefix = "backend-prefix\n".getBytes(StandardCharsets.US_ASCII);
        backend.createContext("/timeout", exchange -> {
            try (exchange) {
                // Фильтр должен зарегистрировать настоящий AsyncListener до первого ответа бэкенда.
                if (!probe.attached.await(3, TimeUnit.SECONDS)) throw new IOException("Probe listener was not attached");
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(prefix);
                exchange.getResponseBody().flush();
                releaseBackend.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        backend.start();
        ConfigurableApplicationContext context = null;
        Thread closer = null;
        try {
            context = new SpringApplicationBuilder(ProxyApplication.class, CompletionProbeConfiguration.class)
                    .initializers(application -> application.getBeanFactory().registerSingleton("completionProbe", probe))
                    .run("--server.port=0", "--server.address=127.0.0.1",
                            "--proxy.backend-url=http://127.0.0.1:" + backend.getAddress().getPort(),
                            "--proxy.max-in-flight=1", "--proxy.exchange-timeout=1s",
                            "--proxy.read-timeout=5s", "--spring.main.banner-mode=off",
                            // При ошибке завершения ожидание ограничено этим интервалом, а не стандартными 30 секундами.
                            "--spring.lifecycle.timeout-per-shutdown-phase=3s");
            int port = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/timeout"))
                        .timeout(Duration.ofSeconds(5)).GET().build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                assertThat(response.statusCode()).isEqualTo(200);
                try (InputStream body = response.body()) {
                    assertThat(body.readNBytes(prefix.length)).isEqualTo(prefix);
                    var read = workers.submit(body::readAllBytes);
                    try {
                        ExecutionException failure = assertThrows(ExecutionException.class,
                                () -> read.get(3, TimeUnit.SECONDS));
                        assertThat(failure.getCause()).isInstanceOf(IOException.class)
                                .isNotInstanceOf(HttpTimeoutException.class);
                    } finally {
                        read.cancel(true);
                    }
                }
            }
            // Проверяем именно onComplete: одного onError или закрытия TCP-соединения недостаточно.
            assertThat(probe.completed.await(2, TimeUnit.SECONDS))
                    .as("real AsyncListener.onComplete after connection abort; onError count=%s", probe.errors.get())
                    .isTrue();
            var transport = context.getBean(ProxyTransport.class);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> {
                assertThat(transport.activeExchanges()).isZero();
                assertThat(transport.activeIoTasks()).isZero();
                assertThat(transport.activeChannels()).isZero();
                assertThat(transport.inFlight.availablePermits()).isEqualTo(1);
            });
            ConfigurableApplicationContext closingContext = context;
            closer = Thread.ofVirtual().name("test-context-close").start(closingContext::close);
            closer.join(2000);
            assertThat(closer.isAlive())
                    .as("context.close must finish before the configured3s graceful-shutdown timeout")
                    .isFalse();
        } finally {
            releaseBackend.countDown();
            if (closer != null) closer.join(5000);
            if (context != null && context.isActive()) context.close();
            backend.stop(0);
            workers.shutdownNow();
        }
    }

    static final class CompletionProbe {
        final CountDownLatch attached = new CountDownLatch(1);
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicInteger errors = new AtomicInteger();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CompletionProbeConfiguration {
        @Bean FilterRegistrationBean<Filter> asyncCompletionProbe(CompletionProbe probe) {
            Filter filter = (request, response, chain) -> {
                chain.doFilter(request, response);
                if (request.isAsyncStarted()) {
                    request.getAsyncContext().addListener(new AsyncListener() {
                        @Override public void onComplete(AsyncEvent event) { probe.completed.countDown(); }
                        @Override public void onError(AsyncEvent event) { probe.errors.incrementAndGet(); }
                        @Override public void onTimeout(AsyncEvent event) { }
                        @Override public void onStartAsync(AsyncEvent event) { }
                    });
                    probe.attached.countDown();
                }
            };
            var registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns("/timeout");
            registration.setDispatcherTypes(DispatcherType.REQUEST);
            registration.setAsyncSupported(true);
            return registration;
        }
    }
}
