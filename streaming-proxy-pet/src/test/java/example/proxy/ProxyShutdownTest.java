package example.proxy;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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

import static org.assertj.core.api.Assertions.assertThat;

/** Остановка транспорта должна завершить зависшую загрузку, её рабочие задачи и каналы. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"proxy.max-in-flight=2", "proxy.shutdown-timeout=2s"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ProxyShutdownTest {
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final CountDownLatch RECEIVED = new CountDownLatch(1);
    private static final CountDownLatch BACKEND_EXITED = new CountDownLatch(1);
    private static final HttpServer BACKEND = startBackend();
    @Autowired ProxyTransport transport;
    @Value("${local.server.port}") int port;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("proxy.backend-url", () -> "http://127.0.0.1:" + BACKEND.getAddress().getPort());
    }

    @AfterAll static void stopBackend() {
        BACKEND.stop(0);
        WORKERS.shutdownNow();
    }

    @Test void shutdownStopsStalledUploadTasksChannelsAndRejectsNewRequests() throws Exception {
        try (Socket uploading = new Socket("127.0.0.1", port)) {
            uploading.setSoTimeout(5000);
            uploading.getOutputStream().write(("POST /stalled HTTP/1.1\r\nHost: localhost\r\n"
                    + "Content-Length: 100000000\r\nConnection: close\r\n\r\nfirst-chunk")
                    .getBytes(StandardCharsets.US_ASCII));
            uploading.getOutputStream().flush();
            assertThat(RECEIVED.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(transport.activeIoTasks()).isPositive();
            long started = System.nanoTime();
            transport.close();
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(6));
            assertThat(BACKEND_EXITED.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(transport.activeChannels()).isZero();
            assertThat(transport.activeIoTasks()).isZero();
            assertThat(transport.activeExchanges()).isZero();
            assertThat(transport.inFlight.availablePermits()).isEqualTo(2);
            assertThat(transport.eventLoops.isTerminated()).isTrue();
            assertThat(transport.servletIo.isTerminated()).isTrue();
        }
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/new"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(transport.inFlight.availablePermits()).isEqualTo(2);
        }
    }

    private static HttpServer startBackend() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(WORKERS);
            server.createContext("/", exchange -> {
                try (exchange) {
                    byte[] chunk = new byte[8192];
                    while (exchange.getRequestBody().read(chunk) != -1) RECEIVED.countDown();
                } catch (IOException expectedAbort) {
                    // Заявленное тело намеренно не дописано: остановка транспорта должна
                    // оборвать загрузку на бэкенде и разблокировать его чтение.
                } finally {
                    BACKEND_EXITED.countDown();
                }
            });
            server.start();
            return server;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
}
