package com.otr.portal.proxy.memory;

import com.otr.portal.command.CommandConfigRegistry;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.ResilienceRegistryConfig;
import com.otr.portal.core.Configuration;
import com.otr.portal.core.internal.PathHelper;
import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.Host;
import com.otr.portal.hosts.HostsService;
import com.otr.portal.hosts.URIGenerator;
import com.otr.portal.proxy.HttpProxy;
import com.otr.portal.proxy.NettyProxyRequestExecutor;
import com.otr.portal.proxy.ProxyCommandFactory;
import com.otr.portal.proxy.ProxyRequestExecutorLookup;
import com.otr.portal.proxy.memory.AllocationProbe.AllocationReport;
import com.otr.portal.proxy.rewriters.CompoundContentRewriter;
import com.otr.portal.proxy.rewriters.ContentRewriter;
import com.otr.portal.proxy.rewriters.FullLinkContentRewriter;
import com.otr.portal.proxy.rewriters.HtmlContentRewriter;
import com.otr.portal.proxy.ssl.NettyTlsFactory;
import com.otr.tests.portal.TestContext;
import io.netty.util.ResourceLeakDetector;
import jakarta.servlet.http.HttpServletRequest;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

/**
 * Замер аллокаций в куче на один проксируемый запрос со страницей ~8 МиБ и на загрузку тела 8 МиБ
 * (встроенный Jetty + асинхронный сервлет + Netty-клиент + MockWebServer).
 * <p>
 * Цель — убедиться, что тело не копируется в кучу: объём кучи на запрос не растёт вместе с телом,
 * и нет аллокаций от 256 КиБ — кандидатов в humongous-объекты G1, из-за которых на стенде росла куча.
 * Считаются только потоки прокси (Jetty, Netty, планировщик), см. {@link AllocationProbe}; потоки клиента
 * и бэкенда в замер не входят. Лимиты взяты с запасом над фактическими значениями: тест ловит возврат
 * копирования тела, а не колебания в десятки килобайт.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(
        webEnvironment = NONE,
        classes = {
                PathHelper.class,
                Configuration.class,
                HttpProxy.class,
                TestContext.class,
                ResilienceRegistryConfig.class,
                CommandConfigRegistry.class,
                CommandResilienceDecorator.class,
                ProxyRequestExecutorLookup.class,
                ProxyCommandFactory.class,
                NettyProxyRequestExecutor.class,
                HtmlContentRewriter.class,
                FullLinkContentRewriter.class
        },
        properties = {
                "spring.cloud.bootstrap.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.cloud.autoconfigure.ConfigurationPropertiesRebinderAutoConfiguration"
        }
)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProxyBodyMemoryTest {
    private static final int PAGE_SIZE = 8 * 1024 * 1024;
    private static final int WARMUP_REQUESTS = 5;
    private static final int MEASURED_REQUESTS = 10;
    private static final long LARGE_ALLOCATION = 256 * 1024;
    private static final long STREAMING_HEAP_LIMIT = 256 * 1024;
    private static final long REWRITING_HEAP_LIMIT = 1024 * 1024;
    private static final long UPLOAD_HEAP_LIMIT = 512 * 1024;
    private static final String REWRITE_PATH = "/HEAPREPRO/index.zul";
    private static final String STREAM_PATH = "/HEAPSTREAM/index.zul";
    private static final String UPLOAD_PATH = "/HEAPSTREAM/upload";
    private static final okhttp3.MediaType OCTET_STREAM = okhttp3.MediaType.get("application/octet-stream");
    private static final Predicate<String> PROXY_THREADS = name -> name.startsWith(EmbeddedPortal.THREAD_NAME_PREFIX)
            || name.startsWith("NettyWorker.")
            || name.startsWith("parallel-");

    @Autowired
    HttpProxy httpProxy;
    @Autowired
    HostsService hostsService;
    @Autowired
    List<ContentRewriter> contentRewriters;
    @MockitoBean
    NettyTlsFactory nettyTlsFactory;

    private MockWebServer backendServer;
    private EmbeddedPortal portal;
    private OkHttpClient client;
    private byte[] page;
    private byte[] rewrittenPage;
    private byte[] upload;

    @BeforeAll
    void startServers() throws Exception {
        hostsService.init();
        backendServer = new MockWebServer();
        backendServer.start(InetAddress.getByName("127.0.0.1"), 0);
        String origin = "http://127.0.0.1:" + backendServer.getPort();
        page = EmulatorPage.buildHtml(PAGE_SIZE, origin).getBytes(UTF_8);
        upload = new byte[PAGE_SIZE];
        new Random(PAGE_SIZE).nextBytes(upload);
        backendServer.setDispatcher(new PageDispatcher(page));

        Backend rewriteBackend = createBackend("heaprepro", "HEAPREPRO", origin, true);
        Backend streamBackend = createBackend("heapstream", "HEAPSTREAM", origin, false);
        rewrittenPage = rewriteLegacy(page, rewriteBackend);
        portal = EmbeddedPortal.start(httpProxy,
                uri -> uri.startsWith("/HEAPREPRO/") ? rewriteBackend : streamBackend, false);
        client = new OkHttpClient.Builder()
                .readTimeout(Duration.ofSeconds(30))
                .build();
    }

    @AfterAll
    void stopServers() throws Exception {
        if (portal != null) {
            portal.close();
        }
        if (backendServer != null) {
            backendServer.shutdown();
        }
    }

    /**
     * Страница 8 МиБ без перезаписи: меньше 256 КиБ кучи на запрос и ни одной крупной аллокации.
     * Тело идёт потоком через direct-буферы Netty прямо в {@code HttpOutput}, в куче — только служебные объекты.
     */
    @Test
    void streamingProxyAllocatesHeapIndependentOfBodySize() throws Exception {
        Measurement measurement = measure(new Scenario("stream", page.length, () -> fetchAndVerify(STREAM_PATH, page)));

        assertThat(measurement.largeAllocations())
                .isZero();
        assertThat(measurement.heapPerRequest())
                .isLessThan(STREAMING_HEAP_LIMIT);
    }

    /**
     * Страница 8 МиБ с перезаписью ссылок: тело накапливается и переписывается в direct-памяти,
     * поэтому кучи меньше 1 МиБ на запрос и нет крупных аллокаций. Первая проверка — что перезапись
     * действительно меняет страницу, иначе тест мерил бы обычную потоковую передачу.
     */
    @Test
    void rewritingProxyAvoidsLargeHeapAllocations() throws Exception {
        assertThat(rewrittenPage).isNotEqualTo(page);

        Measurement measurement = measure(
                new Scenario("rewrite", rewrittenPage.length, () -> fetchAndVerify(REWRITE_PATH, rewrittenPage)));

        assertThat(measurement.largeAllocations())
                .isZero();
        assertThat(measurement.heapPerRequest())
                .isLessThan(REWRITING_HEAP_LIMIT);
    }

    /**
     * Тело запроса читается в pooled direct-память кусками по 64 КиБ: в куче не остаётся ничего,
     * что росло бы вместе с телом, и нет humongous-массивов.
     * Фактически около 300 КиБ на запрос (массив чтения 64 КиБ, буферы и объекты Jetty), лимит — 512 КиБ.
     * Если тело снова начнут копировать в кучу, получится не меньше 8 МиБ на запрос.
     */
    @Test
    void uploadAllocatesHeapIndependentOfBodySize() throws Exception {
        Measurement measurement = measure(new Scenario("upload", upload.length, this::uploadAndVerify));

        assertThat(measurement.largeAllocations()).isZero();
        assertThat(measurement.heapPerRequest()).isLessThan(UPLOAD_HEAP_LIMIT);
    }

    /**
     * Прогрев исключает из замера JIT и ленивую инициализацию, затем замеряется серия запросов.
     * Детектор утечек Netty на время замера выключен: его трассировка сама аллоцирует в куче.
     */
    private Measurement measure(Scenario scenario) throws Exception {
        ResourceLeakDetector.Level leakDetection = ResourceLeakDetector.getLevel();
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        try {
            for (int i = 0; i < WARMUP_REQUESTS; i++) {
                scenario.call.execute();
            }
            System.gc();
            AllocationProbe probe = AllocationProbe.start(PROXY_THREADS, LARGE_ALLOCATION);
            long started = System.nanoTime();
            for (int i = 0; i < MEASURED_REQUESTS; i++) {
                scenario.call.execute();
            }
            long elapsedNanos = System.nanoTime() - started;
            Measurement measurement = new Measurement(probe.stop(), MEASURED_REQUESTS, elapsedNanos);
            System.out.println(measurement.describe(scenario.mode, scenario.bodyBytes));
            return measurement;
        } finally {
            ResourceLeakDetector.setLevel(leakDetection);
        }
    }

    /**
     * Загрузка 8 МиБ через PUT. Бэкенд отвечает размером полученного тела, поэтому замер заодно проверяет,
     * что тело дошло целиком.
     */
    private void uploadAndVerify() throws IOException {
        Request request = new Request.Builder()
                .url(portal.url(UPLOAD_PATH))
                .put(RequestBody.create(upload, OCTET_STREAM))
                .build();
        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().string()).isEqualTo(String.valueOf(upload.length));
        }
    }

    /**
     * Получает страницу и сверяет длину и SHA-256 по ходу чтения, не собирая тело в массив на 8 МиБ.
     * Заголовок {@link EmbeddedPortal#SECURITY_HEADER} подтверждает, что ответ прошёл через фильтр Spring Security.
     */
    private void fetchAndVerify(String path, byte[] expectedBody) throws IOException, NoSuchAlgorithmException {
        Request request = new Request.Builder()
                .url(portal.url(path))
                .header("Accept-Encoding", "identity")
                .build();
        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header(EmbeddedPortal.SECURITY_HEADER)).isEqualTo("on");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long length = 0;
            byte[] chunk = new byte[64 * 1024];
            try (InputStream body = response.body().byteStream()) {
                for (int read = body.read(chunk); read >= 0; read = body.read(chunk)) {
                    digest.update(chunk, 0, read);
                    length += read;
                }
            }
            assertThat(length).isEqualTo(expectedBody.length);
            assertThat(digest.digest()).isEqualTo(MessageDigest.getInstance("SHA-256").digest(expectedBody));
        }
    }

    /**
     * Эталон перезаписи — прежняя строковая цепочка {@code ContentRewriter}.
     */
    private byte[] rewriteLegacy(byte[] body, Backend backend) {
        ContentRewriter chain = new CompoundContentRewriter(contentRewriters.toArray(new ContentRewriter[0]));
        String rewritten = chain.rewrite(MediaType.parseMediaType("text/html; charset=utf-8"),
                new String(body, UTF_8), backend, Mockito.mock(HttpServletRequest.class));
        return rewritten.getBytes(UTF_8);
    }

    private static Backend createBackend(String name, String uri, String target, boolean replaceUrlInContent) {
        Host host = new Host(name, "localhost", target);
        host.setUri(uri);
        host.setReplaceUrlInContent(replaceUrlInContent);
        host.setTimeout(30_000);
        HostsService urls = Mockito.mock(HostsService.class);
        Mockito.when(urls.getUrlGenerator()).thenReturn(new URIGenerator());
        return new Backend(host, 0, urls);
    }

    private static final class PageDispatcher extends Dispatcher {
        private final byte[] page;

        private PageDispatcher(byte[] page) {
            this.page = page;
        }

        /**
         * На загрузку отвечает размером полученного тела, на остальные запросы — страницей.
         */
        @NotNull
        @Override
        public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
            if ("PUT".equals(recordedRequest.getMethod())) {
                return new MockResponse()
                        .setHeader("Content-Type", "text/plain")
                        .setBody(String.valueOf(recordedRequest.getBodySize()));
            }
            return new MockResponse()
                    .setHeader("Content-Type", "text/html; charset=utf-8")
                    .setHeader("Cache-Control", "no-store")
                    .setBody(new Buffer().write(page));
        }
    }

    /**
     * Один проксируемый запрос с проверкой результата.
     */
    @FunctionalInterface
    private interface ProxyCall {
        void execute() throws Exception;
    }

    /**
     * Сценарий замера: имя для отчёта, размер тела и один проксируемый запрос с проверкой результата.
     */
    private static final class Scenario {
        private final String mode;
        private final int bodyBytes;
        private final ProxyCall call;

        private Scenario(String mode, int bodyBytes, ProxyCall call) {
            this.mode = mode;
            this.bodyBytes = bodyBytes;
            this.call = call;
        }
    }

    /**
     * Итог замера в пересчёте на один запрос; {@link #describe} печатает строку {@code [proxy-memory]} для отчёта.
     */
    private static final class Measurement {
        private final AllocationReport report;
        private final int requests;
        private final long elapsedNanos;

        private Measurement(AllocationReport report, int requests, long elapsedNanos) {
            this.report = report;
            this.requests = requests;
            this.elapsedNanos = elapsedNanos;
        }

        long heapPerRequest() {
            return report.getAllocatedBytes() / requests;
        }

        long largeAllocations() {
            return report.getLargeAllocationCount();
        }

        String describe(String mode, int bodyBytes) {
            String topLarge = report.getLargeBytesByClass().entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> e.getKey() + "=" + e.getValue() / requests)
                    .collect(Collectors.joining(", "));
            return String.format(
                    "[proxy-memory] mode=%s body=%d requests=%d heapPerRequest=%d largeCountPerRequest=%.1f "
                            + "largeBytesPerRequest=%d largest=%d latencyMs=%.1f topLarge={%s}",
                    mode, bodyBytes, requests, heapPerRequest(),
                    (double) report.getLargeAllocationCount() / requests,
                    report.getLargeAllocationBytes() / requests,
                    report.getLargestAllocation(),
                    elapsedNanos / 1_000_000.0 / requests,
                    topLarge);
        }
    }
}
