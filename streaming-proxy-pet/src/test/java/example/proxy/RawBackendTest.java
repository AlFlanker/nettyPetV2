package example.proxy;

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
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Бэкенд на TCP-сокете позволяет отправить неполный HTTP-ответ.
 * Обычный HttpServer при нормальном закрытии chunked-ответа дописывает завершающий чанк.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RawBackendTest {
    private static final ExecutorService WORKERS = Executors.newVirtualThreadPerTaskExecutor();
    private static final ServerSocket BACKEND = startBackend();
    @Value("${local.server.port}") int port;
    @Autowired ProxyTransport transport;

    @DynamicPropertySource static void backendUrl(DynamicPropertyRegistry registry) {
        registry.add("proxy.backend-url", () -> "http://127.0.0.1:" + BACKEND.getLocalPort());
    }

    @AfterEach void resourcesTerminate() {
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            assertThat(transport.activeIoTasks()).isZero();
            assertThat(transport.activeChannels()).isZero();
            assertThat(transport.activeExchanges()).isZero();
        });
    }

    @AfterAll static void stop() throws IOException {
        BACKEND.close();
        WORKERS.shutdownNow();
    }

    @Test void missingLastChunkClosesFrontendWithoutInventingSuccessfulEof() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(2000); // Зависание должно провалить тест; оно не считается корректным обрывом ответа.
            socket.getOutputStream().write(("GET /truncated-chunked HTTP/1.1\r\nHost: localhost\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
            assertThat(response).startsWith("HTTP/1.1 200").contains("hello");
            assertThat(response.toLowerCase(java.util.Locale.ROOT)).contains("transfer-encoding: chunked");
            assertThat(response).doesNotEndWith("0\r\n\r\n");
        }
    }

    @Test void informationalResponseDoesNotReplaceFinalResponse() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/informational"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).isEqualTo("OK");
        }
    }

    @Test void validCloseDelimitedAndSinglePacketResponsesCompleteNormally() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            for (String path : java.util.List.of("/close-delimited", "/single-packet")) {
                var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).isEqualTo(path.equals("/close-delimited") ? "close-delimited" : "hello");
            }
        }
    }

    @Test void unsupportedOriginUpgradeIs502InsteadOfAFrontendTunnel() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/unexpected-upgrade"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(502);
        }
    }

    private static ServerSocket startBackend() {
        try {
            var server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            WORKERS.submit(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        WORKERS.submit(() -> respond(socket));
                    } catch (IOException e) {
                        if (!server.isClosed()) throw new RuntimeException(e);
                    }
                }
            });
            return server;
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }

    private static void respond(Socket socket) {
        try (socket) {
            socket.setSoTimeout(5000);
            var head = new ByteArrayOutputStream();
            int matched = 0;
            byte[] end = {'\r', '\n', '\r', '\n'};
            while (matched != end.length) {
                int next = socket.getInputStream().read();
                if (next < 0 || head.size() > 16 * 1024) throw new IOException("Missing request head");
                head.write(next);
                matched = next == end[matched] ? matched + 1 : (next == '\r' ? 1 : 0);
            }
            String request = head.toString(StandardCharsets.US_ASCII);
            String reply;
            if (request.contains(" /truncated-chunked "))
                reply = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n";
            else if (request.contains(" /close-delimited "))
                reply = "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nclose-delimited";
            else if (request.contains(" /single-packet "))
                reply = "HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nhello";
            else if (request.contains(" /unexpected-upgrade "))
                reply = "HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n";
            else reply = "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nOK";
            socket.getOutputStream().write(reply.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
        } catch (IOException e) {
            if (!BACKEND.isClosed()) throw new RuntimeException(e);
        }
    }
}
