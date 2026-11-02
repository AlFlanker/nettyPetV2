package example.proxy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslHandler;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

/**
 * Проверяет штатные Bootstrap/SslHandler на локально созданном сертификате.
 * Тестовое хранилище доверия используется временно; свойства JVM восстанавливаются в finally.
 */
class ProxyTransportTlsTest {
    @TempDir static Path directory;
    private static Path store;
    private static HttpsServer server;
    private static ExecutorService workers;
    private static final char[] PASSWORD = "test-only-password".toCharArray();

    @BeforeAll static void startTlsBackend() throws Exception {
        store = directory.resolve("localhost.p12");
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process generator = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "localhost",
                "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD), "-noprompt")
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertThat(generator.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(generator.exitValue()).isZero();
        } finally { if (generator.isAlive()) generator.destroyForcibly(); }
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(store)) { keys.load(input, PASSWORD); }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keys, PASSWORD);
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keyManagers.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        workers = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            try (exchange) {
                byte[] body = "TLS OK".getBytes(StandardCharsets.US_ASCII);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }

    @AfterAll static void stopTlsBackend() {
        if (server != null) server.stop(0);
        if (workers != null) workers.shutdownNow();
    }

    @Test void defaultTrustRejectsUnknownCertificateAndClosesChannel() throws Exception {
        try (ProxyTransport transport = transport("localhost")) {
            Channel channel = connect(transport);
            try {
                ExecutionException failure = assertThrows(ExecutionException.class,
                        () -> channel.pipeline().get(SslHandler.class).handshakeFuture().get(5, TimeUnit.SECONDS));
                assertThat(failure.getCause()).isInstanceOf(SSLHandshakeException.class);
            } finally { channel.close().syncUninterruptibly(); }
            assertThat(transport.activeChannels()).isZero();
        }
    }

    @Test void trustedCertificateWorksOnlyForMatchingHostname() throws Exception {
        // Тесты выполняются последовательно. В finally восстанавливаем свойства JVM,
        // чтобы временное доверие не влияло на последующие экземпляры транспорта.
        List<String> names = List.of("javax.net.ssl.trustStore", "javax.net.ssl.trustStorePassword", "javax.net.ssl.trustStoreType");
        Map<String, String> original = new HashMap<>();
        names.forEach(name -> original.put(name, System.getProperty(name)));
        try {
            System.setProperty("javax.net.ssl.trustStore", store.toString());
            System.setProperty("javax.net.ssl.trustStorePassword", new String(PASSWORD));
            System.setProperty("javax.net.ssl.trustStoreType", "PKCS12");
            try (ProxyTransport trusted = transport("localhost")) {
                Channel channel = connect(trusted);
                try {
                    channel.pipeline().get(SslHandler.class).handshakeFuture().get(5, TimeUnit.SECONDS);
                    var result = new CompletableFuture<String>();
                    channel.pipeline().replace("backend", "tls-test-response", new SimpleChannelInboundHandler<HttpObject>() {
                        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                        private int status;
                        @Override protected void channelRead0(ChannelHandlerContext context, HttpObject message) {
                            if (message instanceof HttpResponse head) status = head.status().code();
                            if (message instanceof HttpContent content) {
                                byte[] chunk = new byte[content.content().readableBytes()];
                                content.content().readBytes(chunk);
                                bytes.writeBytes(chunk);
                            }
                            if (message instanceof LastHttpContent)
                                result.complete(status + ":" + bytes.toString(StandardCharsets.US_ASCII));
                        }
                        @Override public void exceptionCaught(ChannelHandlerContext context, Throwable error) {
                            result.completeExceptionally(error);
                        }
                        @Override public void channelInactive(ChannelHandlerContext context) {
                            if (!result.isDone()) result.completeExceptionally(new IOException("TLS response truncated"));
                        }
                    });
                    channel.config().setAutoRead(true);
                    var head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
                    head.headers().set(HttpHeaderNames.HOST, "localhost");
                    channel.write(head);
                    channel.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).sync();
                    assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo("200:TLS OK");
                } finally { channel.close().syncUninterruptibly(); }
            }
            // Дополнительно проверяем весь путь приложения без подмены обработчиков:
            // AUTO_READ=false, FlowControlHandler, ProxyCommand и запись в Jetty должны
            // корректно завершить ответ при получении TLS close_notify.
            var application = new org.springframework.boot.SpringApplication(ProxyApplication.class);
            application.setLogStartupInfo(false);
            // При программном запуске нет фильтра тестовых конфигураций от @SpringBootTest.
            // Исключаем соседние тестовые @Configuration, чтобы они не меняли приложение.
            application.addInitializers(context -> context.getBeanFactory().registerSingleton(
                    "tlsTestFixtureExclusion", new org.springframework.boot.context.TypeExcludeFilter() {
                        @Override public boolean match(org.springframework.core.type.classreading.MetadataReader metadata,
                                                       org.springframework.core.type.classreading.MetadataReaderFactory factory) {
                            String name = metadata.getClassMetadata().getClassName();
                            return name.startsWith("example.proxy.") && (name.endsWith("Test") || name.contains("Test$"));
                        }
                        @Override public boolean equals(Object other) { return other == this; }
                        @Override public int hashCode() { return System.identityHashCode(this); }
                    }));
            try (var context = application.run("--server.port=0", "--server.address=127.0.0.1",
                    "--proxy.backend-url=https://localhost:" + server.getAddress().getPort(),
                    "--proxy.exchange-timeout=5s", "--proxy.read-timeout=3s",
                    "--proxy.shutdown-timeout=2s", "--spring.main.banner-mode=off")) {
                int frontendPort = context.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
                ProxyTransport fullTransport = context.getBean(ProxyTransport.class);
                try (var client = java.net.http.HttpClient.newBuilder()
                        .version(java.net.http.HttpClient.Version.HTTP_1_1).build()) {
                    var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                            "http://127.0.0.1:" + frontendPort + "/"))
                            .timeout(Duration.ofSeconds(7)).GET().build();
                    var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
                    assertThat(response.statusCode()).isEqualTo(200);
                    assertThat(response.body()).isEqualTo("TLS OK");
                }
                org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                    assertThat(fullTransport.activeIoTasks()).isZero();
                    assertThat(fullTransport.activeChannels()).isZero();
                    assertThat(fullTransport.activeExchanges()).isZero();
                    assertThat(fullTransport.inFlight.availablePermits()).isEqualTo(fullTransport.properties.maxInFlight());
                });
            }
            // Сертификату уже доверяем, но его SAN содержит localhost, а не IP 127.0.0.1:
            // теперь соединение должно отклониться именно из-за несовпадения имени.
            try (ProxyTransport wrongHost = transport("127.0.0.1")) {
                Channel channel = connect(wrongHost);
                try {
                    ExecutionException failure = assertThrows(ExecutionException.class,
                            () -> channel.pipeline().get(SslHandler.class).handshakeFuture().get(5, TimeUnit.SECONDS));
                    assertThat(failure.getCause()).isInstanceOf(SSLHandshakeException.class);
                } finally { channel.close().syncUninterruptibly(); }
                assertThat(wrongHost.activeChannels()).isZero();
            }
        } finally {
            names.forEach(name -> {
                String previous = original.get(name);
                if (previous == null) System.clearProperty(name);
                else System.setProperty(name, previous);
            });
        }
    }

    private static ProxyTransport transport(String host) {
        return new ProxyTransport(new ProxyProperties("https://" + host + ":" + server.getAddress().getPort(),
                2, 1, 16 * 1024, Duration.ofSeconds(3), Duration.ofSeconds(3),
                Duration.ofSeconds(10), Duration.ofSeconds(2)));
    }

    private static Channel connect(ProxyTransport transport) throws Exception {
        var exchange = new ProxyCommand(mock(HttpServletRequest.class), mock(HttpServletResponse.class),
                mock(AsyncContext.class), transport);
        // TCP-соединение устанавливаем по IPv4; TLS проверяет имя из настроенного адреса бэкенда.
        return transport.bootstrap(exchange).connect("127.0.0.1", server.getAddress().getPort()).sync().channel();
    }
}
