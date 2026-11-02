package com.otr.portal.proxy.memory;

import com.otr.portal.command.CommandConfigRegistry;
import com.otr.portal.command.CommandResilienceDecorator;
import com.otr.portal.command.ResilienceRegistryConfig;
import com.otr.portal.core.Configuration;
import com.otr.portal.core.internal.PathHelper;
import com.otr.portal.core.internal.ProxiedRequestMatcher;
import com.otr.portal.core.support.jetty.ProxyAwareMultipartFilter;
import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.Host;
import com.otr.portal.hosts.HostsService;
import com.otr.portal.hosts.URIGenerator;
import com.otr.portal.hosts.filter.ProxyAwareFormContentFilter;
import com.otr.portal.proxy.HttpProxy;
import com.otr.portal.proxy.NettyProxyRequestExecutor;
import com.otr.portal.proxy.ProxyCommandFactory;
import com.otr.portal.proxy.ProxyRequestExecutorLookup;
import com.otr.portal.proxy.rewriters.CompoundContentRewriter;
import com.otr.portal.proxy.rewriters.ContentRewriter;
import com.otr.portal.proxy.rewriters.FullLinkContentRewriter;
import com.otr.portal.proxy.rewriters.HtmlContentRewriter;
import com.otr.portal.proxy.ssl.NettyTlsFactory;
import com.otr.tests.portal.TestContext;
import jakarta.servlet.Filter;
import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.HttpServletRequest;
import okhttp3.*;
import okhttp3.mockwebserver.*;
import okhttp3.mockwebserver.Dispatcher;
import okio.Buffer;
import okio.BufferedSink;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.filter.CharacterEncodingFilter;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.MultipartFilter;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLEncoder;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static com.otr.portal.hosts.filter.BackendForRequestFilter.BACKEND_ATTRIBUTE_NAME;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.NONE;

/**
 * Корректность передачи тела через {@link HttpProxy} на встроенном Jetty: целостность, перезапись,
 * gzip, медленный клиент, обрывы, таймауты, лимит размера и отсутствие утечек буферов Netty.
 * Тело запроса: обычное и chunked, формы и multipart побайтово и через {@code HttpPostRequestEncoder},
 * недоступный бэкенд и обрыв загрузки клиентом.
 * <p>
 * Каждый тест проходит весь путь: OkHttp → Jetty (асинхронный сервлет, фильтры) → {@link HttpProxy} → Netty →
 * MockWebServer. Бэкенд записывает полученные запросы, поэтому для загрузок проверяется то, что реально
 * пришло в бэкенд, а не только ответ клиенту. Детектор утечек Netty работает на весь класс,
 * и в {@code @AfterAll} проверяется, что ни один тест не оставил неосвобождённых буферов.
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
class ProxyBodyFunctionalTest {
    private static final int PAGE_SIZE = 1024 * 1024;
    private static final int LARGE_PAGE_SIZE = 4 * 1024 * 1024;
    private static final int OVERSIZED_PAGE_SIZE = 3 * 1024 * 1024;
    private static final int REWRITE_LIMIT_MB = 2;
    private static final int CHUNK_SIZE = 8 * 1024;
    private static final String BACKEND_FAILURE_MESSAGE = "При запросе данных подсистемы произошла ошибка";
    private static final String BACKEND_UNAVAILABLE_MESSAGE = "Запрашиваемая подсистема недоступна";
    private static final int UPLOAD_SIZE = 4 * 1024 * 1024 + 123;
    private static final int CHUNKED_UPLOAD_SIZE = 1024 * 1024 + 77;
    private static final int MULTIPART_FILE_SIZE = 256 * 1024;
    private static final int ABORTED_UPLOAD_SENT = 192 * 1024 + 100;
    /**
     * Элементов JSON в поле формы: в urlencoded около 50 КиБ, как PUT-форма из HAR (51128 байт).
     */
    private static final int FORM_ITEMS = 1000;
    private static final String UPLOAD_ACCEPTED = "accepted";
    private static final List<String> UPLOAD_NAMES = List.of("upload", "upload-chunked", "upload-json",
            "form-small", "form-large", "multipart", "form-put", "form-post", "multipart-raw");
    private static final okhttp3.MediaType OCTET_STREAM = okhttp3.MediaType.get("application/octet-stream");
    private static final okhttp3.MediaType JSON = okhttp3.MediaType.get("application/json; charset=utf-8");
    private static final okhttp3.MediaType FORM_URLENCODED = okhttp3.MediaType.get("application/x-www-form-urlencoded");
    private static final Pattern PART_NAME = Pattern.compile("(?:^|;)\\s*name=\"([^\"]*)\"");

    @Autowired
    HttpProxy httpProxy;
    @Autowired
    HostsService hostsService;
    @Autowired
    Configuration configuration;
    @Autowired
    List<ContentRewriter> contentRewriters;
    @MockitoBean
    NettyTlsFactory nettyTlsFactory;

    private final Map<String, Supplier<MockResponse>> responses = new HashMap<>();
    private final Queue<String> backendRequests = new ConcurrentLinkedQueue<>();
    private final Map<String, RecordedRequest> recordedRequests = new ConcurrentHashMap<>();
    private final Map<String, Backend> backends = new HashMap<>();
    private NettyLeakRecorder leakRecorder;
    private MockWebServer backendServer;
    private EmbeddedPortal portal;
    private EmbeddedPortal gzipPortal;
    private EmbeddedPortal uploadPortal;
    private EmbeddedPortal passthroughPortal;
    private OkHttpClient client;
    private byte[] upload;
    private byte[] page;
    private byte[] largePage;
    private byte[] rewrittenPage;
    private byte[] image;
    private byte[] gzippedPage;

    /**
     * Один MockWebServer на все хосты и четыре портала: обычный, с Jetty {@code GzipHandler}, с фильтрами,
     * читающими тело до прокси, и с фильтрами портала, которые проксируемое тело не трогают.
     * Хост {@code UNREACHABLE} смотрит на закрытый порт.
     */
    @BeforeAll
    void startServers() throws Exception {
        leakRecorder = NettyLeakRecorder.start();
        hostsService.init();
        backendServer = new MockWebServer();
        backendServer.start(InetAddress.getByName("127.0.0.1"), 0);
        String origin = "http://127.0.0.1:" + backendServer.getPort();
        page = EmulatorPage.buildHtml(PAGE_SIZE, origin).getBytes(UTF_8);
        largePage = EmulatorPage.buildHtml(LARGE_PAGE_SIZE, origin).getBytes(UTF_8);
        image = ("\u0089PNG\r\n<a href=\"/must-not-change\">" + "x".repeat(PAGE_SIZE)).getBytes(UTF_8);
        gzippedPage = gzip(page);
        upload = randomBytes(UPLOAD_SIZE);
        registerResponses(EmulatorPage.buildHtml(OVERSIZED_PAGE_SIZE, origin).getBytes(UTF_8));
        backendServer.setDispatcher(new RoutingDispatcher(responses, backendRequests, recordedRequests));

        String unreachableOrigin = closedPortOrigin();
        for (TestBackend testBackend : TestBackend.values()) {
            backends.put(testBackend.uri,
                    createBackend(testBackend, testBackend == TestBackend.UNREACHABLE ? unreachableOrigin : origin));
        }
        rewrittenPage = rewriteLegacy(page, backends.get(TestBackend.REWRITE.uri));
        portal = EmbeddedPortal.start(httpProxy, this::resolveBackend, false);
        gzipPortal = EmbeddedPortal.start(httpProxy, this::resolveBackend, true);
        uploadPortal = EmbeddedPortal.startWithFilters(httpProxy, this::resolveBackend, portalRequestFilters());
        passthroughPortal = EmbeddedPortal.startWithFilters(httpProxy, this::resolveBackend, passthroughRequestFilters());
        client = new OkHttpClient.Builder()
                .readTimeout(Duration.ofSeconds(20))
                .retryOnConnectionFailure(false)
                .build();
    }

    /**
     * Утечки проверяются после остановки серверов: к этому моменту все каналы закрыты,
     * и любой неосвобождённый буфер — ошибка какого-то из тестов класса.
     */
    @AfterAll
    void stopServersAndCheckLeaks() throws Exception {
        try {
            portal.close();
            gzipPortal.close();
            uploadPortal.close();
            passthroughPortal.close();
            backendServer.shutdown();
            assertThat(leakRecorder.collectLeaks()).isEmpty();
        } finally {
            leakRecorder.close();
        }
    }

    /**
     * Страница 1 МиБ без перезаписи идёт клиенту потоком: тело совпадает побайтово, {@code Content-Length}
     * бэкенда сохраняется. Базовый сценарий: потоковая передача не теряет и не дублирует куски.
     */
    @Test
    void streamsBodyWithContentLength() throws IOException {
        try (Response response = fetch(portal, "/FUNCSTREAM/page")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Content-Length")).isEqualTo(String.valueOf(page.length));
            assertThat(response.body().bytes()).isEqualTo(page);
        }
    }

    /**
     * Chunked-ответ бэкенда (куски по 8 КиБ) без перезаписи доходит до клиента целиком:
     * тело собирается из множества мелких {@code HttpContent} без потерь.
     */
    @Test
    void streamsChunkedBody() throws IOException {
        try (Response response = fetch(portal, "/FUNCSTREAM/chunked")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().bytes()).isEqualTo(page);
        }
    }

    /**
     * Chunked HTML на хосте с перезаписью ссылок: прокси накапливает текст, переписывает его и отдаёт
     * с {@code Content-Length} вместо chunked. Эталон — прежняя строковая цепочка {@code ContentRewriter}:
     * так подтверждается, что перезапись на байтах даёт тот же результат.
     */
    @Test
    void rewritesChunkedBodyAndSetsContentLength() throws IOException {
        try (Response response = fetch(portal, "/FUNCREWRITE/chunked")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Transfer-Encoding")).isNull();
            assertThat(response.header("Content-Length")).isEqualTo(String.valueOf(rewrittenPage.length));
            assertThat(response.body().bytes()).isEqualTo(rewrittenPage);
        }
    }

    /**
     * Картинка на хосте с перезаписью не меняется, хотя внутри есть текст ссылки: решение о перезаписи
     * принимается по {@code Content-Type}, иначе бинарные файлы портились бы.
     */
    @Test
    void streamsBinaryBodyOnRewriteHost() throws IOException {
        try (Response response = fetch(portal, "/FUNCREWRITE/image")) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.body().bytes()).isEqualTo(image);
        }
    }

    /**
     * Сжатый gzip HTML на хосте с перезаписью отдаётся как есть, с {@code Content-Encoding: gzip}:
     * переписывать сжатые байты нельзя — клиент получил бы испорченный архив.
     */
    @Test
    void streamsEncodedBodyOnRewriteHostUnchanged() throws IOException {
        Request request = new Request.Builder()
                .url(portal.url("/FUNCREWRITE/gzip"))
                .header("Accept-Encoding", "gzip")
                .build();
        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Content-Encoding")).isEqualTo("gzip");
            assertThat(response.body().bytes()).isEqualTo(gzippedPage);
        }
    }

    /**
     * Jetty {@code GzipHandler} сжимает и потоковое, и переписанное тело, а после распаковки оно совпадает с эталоном.
     * Проверяет, что асинхронная запись прокси в {@code HttpOutput} совместима с gzip-обёрткой ответа.
     */
    @Test
    void compressesStreamedAndRewrittenBodiesWithJettyGzip() throws IOException {
        assertThat(fetchGzipped("/FUNCSTREAM/page")).isEqualTo(page);
        assertThat(fetchGzipped("/FUNCREWRITE/page")).isEqualTo(rewrittenPage);
    }

    /**
     * Медленный клиент (по 16 КиБ с паузой) получает всю страницу 4 МиБ. Чтение от бэкенда идёт по спросу клиента
     * (обратное давление): тест ловит потерю данных и зависание, когда клиент не успевает за бэкендом.
     */
    @Test
    void slowClientReceivesCompleteBody() throws Exception {
        try (Response response = fetch(portal, "/FUNCSTREAM/large")) {
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            try (InputStream body = response.body().byteStream()) {
                for (int read = body.read(chunk); read >= 0; read = body.read(chunk)) {
                    received.write(chunk, 0, read);
                    Thread.sleep(1);
                }
            }
            assertThat(received.toByteArray()).isEqualTo(largePage);
        }
    }

    /**
     * Клиент трижды закрывает ответ, прочитав 64 КиБ из 4 МиБ. Прокси прекращает обмен с бэкендом, освобождает
     * буферы (проверяется в {@code @AfterAll}) и обслуживает следующий запрос — потоки и каналы не зависли.
     */
    @Test
    void clientAbortKeepsProxyHealthy() throws IOException {
        for (int i = 0; i < 3; i++) {
            try (Response response = fetch(portal, "/FUNCSTREAM/large")) {
                assertThat(response.body().byteStream().read(new byte[64 * 1024])).isPositive();
            }
        }
        streamsBodyWithContentLength();
    }

    /**
     * Бэкенд рвёт соединение посреди тела. Заголовки клиенту уже отправлены, статус ошибки не передать,
     * поэтому клиент должен получить ошибку чтения, а не тело, обрезанное без всяких признаков.
     */
    @Test
    void backendAbortDuringBodyTruncatesResponse() {
        assertThatThrownBy(() -> {
            try (Response response = fetch(portal, "/FUNCSTREAM/cut")) {
                response.body().bytes();
            }
        }).isInstanceOf(IOException.class);
    }

    /**
     * Бэкенд закрыл соединение, не отправив заголовков: клиент получает 500 и стандартное сообщение портала.
     */
    @Test
    void backendAbortBeforeHeadersReturnsError() throws IOException {
        try (Response response = fetch(portal, "/FUNCSTREAM/drop")) {
            assertThat(response.code()).isEqualTo(500);
            assertThat(response.body().string()).isEqualTo(BACKEND_FAILURE_MESSAGE);
        }
    }

    /**
     * Заголовки бэкенда приходят позже таймаута хоста (700 мс): клиент получает 408 и сообщение о недоступности.
     */
    @Test
    void timeoutBeforeHeadersReturnsRequestTimeout() throws IOException {
        try (Response response = fetch(portal, "/FUNCSLOW/late-headers")) {
            assertThat(response.code()).isEqualTo(408);
            assertThat(response.body().string()).isEqualTo(BACKEND_UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * Заголовки пришли вовремя, а тело опаздывает дольше таймаута хоста: клиент всё равно получает 408
     * с сообщением портала, а не начатый ответ 200, который потом оборвётся.
     */
    @Test
    void timeoutAfterHeadersReturnsRequestTimeout() throws IOException {
        try (Response response = fetch(portal, "/FUNCSLOW/late-body")) {
            assertThat(response.code()).isEqualTo(408);
            assertThat(response.body().string()).isEqualTo(BACKEND_UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * С keep-alive три запроса — страница с перезаписью, картинка потоком и снова страница — идут по одному
     * соединению с бэкендом (номера запросов на соединении 0, 1, 2) и получают правильные тела.
     * Проверяет, что после ответа любого вида канал возвращается в пул без недочитанного хвоста.
     */
    @Test
    void reusesKeepAliveConnectionForStreamedAndRewrittenBodies() throws IOException {
        byte[] expectedPage = rewriteLegacy(page, backends.get(TestBackend.KEEP_ALIVE.uri));
        boolean previousKeepAlive = configuration.isKeepAlive();
        configuration.setKeepAlive(true);
        backendRequests.clear();
        try {
            for (String path : List.of("/FUNCKEEPALIVE/page", "/FUNCKEEPALIVE/image", "/FUNCKEEPALIVE/page")) {
                try (Response response = fetch(portal, path)) {
                    assertThat(response.code()).as(path).isEqualTo(200);
                    assertThat(response.body().bytes()).as(path)
                            .isEqualTo(path.endsWith("image") ? image : expectedPage);
                }
            }
        } finally {
            configuration.setKeepAlive(previousKeepAlive);
        }
        assertThat(backendRequests).containsExactly("page#0", "image#1", "page#2");
    }

    /**
     * HTML для перезаписи больше {@code maxContentLengthMb} (страница 3 МиБ при лимите 2), с {@code Content-Length}
     * и chunked: клиент получает 500. Лимит не даёт накапливать для перезаписи тело без предела.
     */
    @Test
    void rewriteBodyAboveLimitReturnsError() throws IOException {
        Integer previousLimit = configuration.getMaxContentLengthMb();
        configuration.setMaxContentLengthMb(REWRITE_LIMIT_MB);
        try {
            for (String path : List.of("/FUNCREWRITE/oversized", "/FUNCREWRITE/oversized-chunked")) {
                try (Response response = fetch(portal, path)) {
                    assertThat(response.code()).as(path).isEqualTo(500);
                    assertThat(response.body().string()).as(path).isEqualTo(BACKEND_FAILURE_MESSAGE);
                }
            }
        } finally {
            configuration.setMaxContentLengthMb(previousLimit);
        }
    }

    /**
     * Загрузка 4 МиБ с {@code Content-Length}: бэкенд получает те же байты и ту же длину, без {@code Transfer-Encoding}.
     * Основной сценарий чтения тела в direct-куски, проверенный на стороне бэкенда.
     */
    @Test
    void forwardsRequestBodyWithContentLength() throws IOException {
        RecordedRequest recorded = sendToBackend(uploadRequest("/FUNCUPLOAD/upload")
                .put(RequestBody.create(upload, OCTET_STREAM)));

        assertThat(recorded.getHeader("Content-Length")).isEqualTo(String.valueOf(upload.length));
        assertThat(recorded.getHeader("Transfer-Encoding")).isNull();
        assertThat(recorded.getBody().readByteArray()).isEqualTo(upload);
    }

    /**
     * Тело от клиента приходит chunked, но прокси читает его целиком и отправляет бэкенду с Content-Length.
     * Оба заголовка сразу запрещены RFC 7230: сервер, читающий по {@code Content-Length},
     * получил бы тело вместе с разметкой кусков, а строгие серверы отклонили бы запрос.
     */
    @Test
    void forwardsChunkedRequestBodyWithContentLength() throws IOException {
        byte[] body = Arrays.copyOf(upload, CHUNKED_UPLOAD_SIZE);

        RecordedRequest recorded = sendToBackend(uploadRequest("/FUNCUPLOAD/upload-chunked").put(streamingBody(body)));

        assertThat(recorded.getHeader("Transfer-Encoding")).isNull();
        assertThat(recorded.getHeader("Content-Length")).isEqualTo(String.valueOf(body.length));
        assertThat(recorded.getBody().readByteArray()).isEqualTo(body);
    }

    /**
     * POST с JSON — не форма: параметров нет, encoder не задействуется, и тело уходит побайтово
     * с исходным {@code Content-Type}. Ловит ошибочную пересборку не-формы через {@code HttpPostRequestEncoder}.
     */
    @Test
    void forwardsJsonPostBodyAsIs() throws IOException {
        byte[] json = "{\"name\":\"значение\"}".getBytes(UTF_8);

        RecordedRequest recorded = sendToBackend(uploadRequest("/FUNCUPLOAD/upload-json")
                .post(RequestBody.create(json, JSON)));

        assertThat(recorded.getHeader("Content-Type")).startsWith("application/json");
        assertThat(recorded.getBody().readByteArray()).isEqualTo(json);
    }

    /**
     * Параметры формы прочитаны фильтром до прокси, поэтому тело собирает заново {@code HttpPostRequestEncoder};
     * маленькая форма пишется в тот же буфер тела запроса.
     * Бэкенд получает ту же форму (с кириллицей), без chunked и с {@code Content-Length} пересобранного тела.
     */
    @Test
    void rebuildsSmallFormThroughEncoder() throws IOException {
        FormBody form = new FormBody.Builder(UTF_8).add("a", "1").add("name", "значение").build();

        RecordedRequest recorded = sendToBackend(uploadRequest("/FUNCUPLOAD/form-small").post(form));

        String body = recorded.getBody().readUtf8();
        assertThat(recorded.getHeader("Transfer-Encoding")).isNull();
        assertThat(recorded.getHeader("Content-Length")).isEqualTo(String.valueOf(body.getBytes(UTF_8).length));
        assertThat(UrlEncodedForm.parse(body)).isEqualTo(Map.of("a", List.of("1"), "name", List.of("значение")));
    }

    /**
     * Форма больше 8 КиБ уходит из encoder'а по частям, а исходное тело запроса освобождается при сборке.
     * Бэкенд получает chunked-тело, которое складывается в исходную форму; утечку исходного тела
     * поймает проверка в {@code @AfterAll}.
     */
    @Test
    void rebuildsLargeFormThroughEncoderInChunks() throws IOException {
        String value = "значение ".repeat(1024);
        FormBody form = new FormBody.Builder(UTF_8).add("big", value).build();

        RecordedRequest recorded = sendToBackend(uploadRequest("/FUNCUPLOAD/form-large").post(form));

        assertThat(recorded.getHeader("Transfer-Encoding")).isEqualTo("chunked");
        assertThat(UrlEncodedForm.parse(recorded.getBody().readUtf8())).isEqualTo(Map.of("big", List.of(value)));
    }

    /**
     * {@code MultipartFilter} кладёт файл во временный файл и удаляет его после возврата из цепочки фильтров;
     * прокси успевает прочитать файл, потому что собирает запрос синхронно, до этой очистки.
     * Бэкенд получает ровно две части — поле с кириллицей и файл 256 КиБ — без изменений.
     * Сравниваются части, а не тело целиком: encoder пересобирает multipart со своим boundary.
     */
    @Test
    void rebuildsMultipartFormWithFile() throws IOException {
        byte[] file = Arrays.copyOf(upload, MULTIPART_FILE_SIZE);
        MultipartBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("comment", "значение")
                .addFormDataPart("file", "upload.bin", RequestBody.create(file, OCTET_STREAM))
                .build();

        Map<String, byte[]> parts = readMultipartParts(sendToBackend(uploadRequest("/FUNCUPLOAD/multipart").post(multipart)));

        assertThat(parts).containsOnlyKeys("comment", "file");
        assertThat(new String(parts.get("comment"), UTF_8)).isEqualTo("значение");
        assertThat(parts.get("file")).isEqualTo(file);
    }

    /**
     * PUT-форма около 50 КиБ (как в HAR) доходит до бэкенда побайтово и с длиной по телу.
     * Раньше {@code FormContentFilter} вычитывал тело в параметры: бэкенд получал пустое тело
     * с {@code Content-Length: 51128}, ждал недостающие байты, и клиент получал 504.
     */
    @Test
    void forwardsPutFormAsIs() throws IOException {
        byte[] form = largeForm();

        RecordedRequest recorded = sendToBackend(passthroughRequest("/FUNCUPLOAD/form-put")
                .put(RequestBody.create(form, FORM_URLENCODED)));

        assertForwardedAsIs(recorded, form);
    }

    /**
     * POST-форму больше порога encoder'а до прокси никто не читает, поэтому она уходит побайтово,
     * без chunked-пересборки через {@code HttpPostRequestEncoder}.
     */
    @Test
    void forwardsPostFormAsIs() throws IOException {
        byte[] form = largeForm();

        RecordedRequest recorded = sendToBackend(passthroughRequest("/FUNCUPLOAD/form-post")
                .post(RequestBody.create(form, FORM_URLENCODED)));

        assertForwardedAsIs(recorded, form);
    }

    /**
     * Multipart к бэкенду {@code MultipartFilter} не разбирает: бэкенд получает исходное тело с тем же boundary,
     * заголовками и порядком частей, кириллическим именем файла, без временных файлов и пересборки через encoder.
     */
    @Test
    void forwardsMultipartAsIs() throws IOException {
        MultipartBody multipart = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("comment", "значение")
                .addFormDataPart("file", "отчёт.bin",
                        RequestBody.create(Arrays.copyOf(upload, MULTIPART_FILE_SIZE), OCTET_STREAM))
                .build();
        Buffer body = new Buffer();
        multipart.writeTo(body);

        RecordedRequest recorded = sendToBackend(passthroughRequest("/FUNCUPLOAD/multipart-raw").post(multipart));

        assertThat(okhttp3.MediaType.get(recorded.getHeader("Content-Type")).parameter("boundary"))
                .isEqualTo(multipart.boundary());
        assertForwardedAsIs(recorded, body.readByteArray());
    }

    /**
     * Бэкенд недоступен (порт закрыт), а тело 4 МиБ уже прочитано: клиент получает 408 с сообщением
     * о недоступности. Тело в канал не ушло, его освобождает обработчик ошибки подключения —
     * без этого освобождения упадёт проверка утечек в {@code @AfterAll}.
     */
    @Test
    void unreachableBackendReturnsTimeoutForUpload() throws IOException {
        Request request = uploadRequest("/FUNCUNREACHABLE/upload").put(RequestBody.create(upload, OCTET_STREAM)).build();

        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(408);
            assertThat(response.body().string()).isEqualTo(BACKEND_UNAVAILABLE_MESSAGE);
        }
    }

    /**
     * Клиент закрывает соединение посреди тела: чтение падает до подключения к бэкенду,
     * уже прочитанные куски освобождаются (проверяется детектором утечек в {@code @AfterAll}).
     * Запрос пишется в сырой сокет: OkHttp не умеет оборвать тело посреди отправки.
     * После обрыва обычная загрузка проходит, а оборванный запрос до бэкенда не дошёл.
     */
    @Test
    void clientAbortDuringUploadKeepsProxyHealthy() throws Exception {
        try (Socket socket = new Socket(InetAddress.getByName("127.0.0.1"), uploadPortal.port())) {
            OutputStream out = socket.getOutputStream();
            String head = "PUT /FUNCUPLOAD/upload-aborted HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + uploadPortal.port() + "\r\n"
                    + "Content-Type: application/octet-stream\r\n"
                    + "Content-Length: " + upload.length + "\r\n\r\n";
            out.write(head.getBytes(US_ASCII));
            out.write(upload, 0, ABORTED_UPLOAD_SENT);
            out.flush();
        }

        forwardsRequestBodyWithContentLength();
        assertThat(recordedRequests).doesNotContainKey("upload-aborted");
    }

    /**
     * Ответы бэкенда по последнему сегменту пути: страницы, chunked, картинка, gzip, обрывы, задержки,
     * страница больше лимита перезаписи; на загрузки — короткое {@link #UPLOAD_ACCEPTED}.
     */
    private void registerResponses(byte[] oversizedPage) {
        responses.put("page", () -> htmlResponse().setBody(new Buffer().write(page)));
        responses.put("large", () -> htmlResponse().setBody(new Buffer().write(largePage)));
        responses.put("chunked", () -> htmlResponse().setChunkedBody(new Buffer().write(page), CHUNK_SIZE));
        responses.put("image", () -> new MockResponse()
                .setHeader("Content-Type", "image/png")
                .setBody(new Buffer().write(image)));
        responses.put("gzip", () -> htmlResponse()
                .setHeader("Content-Encoding", "gzip")
                .setBody(new Buffer().write(gzippedPage)));
        responses.put("cut", () -> htmlResponse()
                .setBody(new Buffer().write(largePage))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));
        responses.put("drop", () -> new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST));
        responses.put("late-headers", () -> htmlResponse()
                .setBody(new Buffer().write(page))
                .setHeadersDelay(3, TimeUnit.SECONDS));
        responses.put("late-body", () -> htmlResponse()
                .setBody(new Buffer().write(page))
                .setBodyDelay(3, TimeUnit.SECONDS));
        responses.put("oversized", () -> htmlResponse().setBody(new Buffer().write(oversizedPage)));
        responses.put("oversized-chunked", () -> htmlResponse()
                .setChunkedBody(new Buffer().write(oversizedPage), CHUNK_SIZE));
        UPLOAD_NAMES.forEach(name -> responses.put(name, () -> new MockResponse()
                .setHeader("Content-Type", "text/plain")
                .setBody(UPLOAD_ACCEPTED)));
    }

    /**
     * Хост по первому сегменту пути — как {@code BackendForRequestFilter} в портале.
     */
    private Backend resolveBackend(String uri) {
        return backends.get(uri.split("/")[1]);
    }

    /**
     * Фильтры, которые читают тело до прокси: кодировка запроса (как в Spring Boot), разбор multipart всех запросов
     * (как {@code MultiPartFilterRegistration} с {@code portal.proxy.multipart-passthrough=false}) и чтение
     * параметров формы. Без них прокси получил бы непрочитанное тело, и пути пересборки форм и multipart
     * не проверялись бы. У сервлета прокси, как и у проксируемых путей портала, нет multipart-конфигурации:
     * её подставляет фильтр.
     */
    private static List<Filter> portalRequestFilters() {
        Filter parameterReadingFilter = (request, response, chain) -> {
            request.getParameterMap();
            chain.doFilter(request, response);
        };
        return List.of(new CharacterEncodingFilter(UTF_8.name(), true, false),
                multipartFilter(request -> false), parameterReadingFilter);
    }

    /**
     * Фильтры портала в порядке регистрации: атрибут бэкенда (как {@code BackendForRequestFilter}), кодировка запроса,
     * {@link ProxyAwareFormContentFilter} и {@link ProxyAwareMultipartFilter} с признаком проксируемого запроса
     * из {@code ProxySpringConfiguration}. Параметры до прокси никто не читает, как и в портале.
     */
    private List<Filter> passthroughRequestFilters() {
        ProxiedRequestMatcher proxiedRequests = request -> request.getAttribute(BACKEND_ATTRIBUTE_NAME) != null;
        Filter backendFilter = (request, response, chain) -> {
            request.setAttribute(BACKEND_ATTRIBUTE_NAME, resolveBackend(((HttpServletRequest) request).getRequestURI()));
            chain.doFilter(request, response);
        };
        return List.of(backendFilter, new CharacterEncodingFilter(UTF_8.name(), true, false),
                new ProxyAwareFormContentFilter(proxiedRequests), multipartFilter(proxiedRequests));
    }

    /**
     * Разбор multipart, как в {@code MultiPartFilterRegistration}; резолвер задан напрямую, без контекста Spring.
     */
    private static MultipartFilter multipartFilter(ProxiedRequestMatcher proxiedRequests) {
        StandardServletMultipartResolver multipartResolver = new StandardServletMultipartResolver();
        return new ProxyAwareMultipartFilter(proxiedRequests, new MultipartConfigElement("")) {
            @Override
            protected MultipartResolver lookupMultipartResolver() {
                return multipartResolver;
            }
        };
    }

    /**
     * Запрос к порталу с фильтрами; без сжатия ответа, чтобы ответ бэкенда сравнивался как есть.
     */
    private Request.Builder uploadRequest(String path) {
        return portalRequest(uploadPortal, path);
    }

    /**
     * Запрос к порталу, который передаёт тело проксируемого запроса как есть.
     */
    private Request.Builder passthroughRequest(String path) {
        return portalRequest(passthroughPortal, path);
    }

    private static Request.Builder portalRequest(EmbeddedPortal target, String path) {
        return new Request.Builder()
                .url(target.url(path))
                .header("Accept-Encoding", "identity");
    }

    /**
     * Бэкенд получил тело клиента без изменений: те же байты и {@code Content-Length}, без {@code Transfer-Encoding}.
     */
    private static void assertForwardedAsIs(RecordedRequest recorded, byte[] body) {
        assertThat(recorded.getHeader("Transfer-Encoding")).isNull();
        assertThat(recorded.getHeader("Content-Length")).isEqualTo(String.valueOf(body.length));
        assertThat(recorded.getBody().readByteArray()).isEqualTo(body);
    }

    /**
     * Форма с одним полем {@code content}, в котором JSON с кириллицей, как в PUT-запросе из HAR.
     */
    private static byte[] largeForm() {
        String json = "{\"name\":\"значение\",\"items\":["
                + String.join(",", Collections.nCopies(FORM_ITEMS, "\"элемент\"")) + "]}";
        return ("content=" + URLEncoder.encode(json, UTF_8)).getBytes(UTF_8);
    }

    /**
     * Выполняет запрос через портал и возвращает запрос, который получил бэкенд.
     * Ответ 200 с телом бэкенда подтверждает, что запрос прошёл через прокси, а не отклонён по дороге.
     */
    private RecordedRequest sendToBackend(Request.Builder request) throws IOException {
        Request built = request.build();
        try (Response response = client.newCall(built).execute()) {
            assertThat(response.code())
                    .isEqualTo(200);
            Assertions.assertNotNull(response.body());
            assertThat(response.body().string())
                    .isEqualTo(UPLOAD_ACCEPTED);
        }
        String path = built.url().encodedPath();
        return recordedRequests.get(path.substring(path.lastIndexOf('/') + 1));
    }

    /**
     * Части multipart, которые получил бэкенд, в порядке следования: имя части → содержимое.
     */
    private static Map<String, byte[]> readMultipartParts(RecordedRequest recorded) throws IOException {
        String boundary = okhttp3.MediaType.get(recorded.getHeader("Content-Type")).parameter("boundary");
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (MultipartReader reader = new MultipartReader(recorded.getBody(), boundary)) {
            for (MultipartReader.Part part = reader.nextPart(); part != null; part = reader.nextPart()) {
                try (MultipartReader.Part current = part) {
                    parts.put(partName(current.headers().get("Content-Disposition")), current.body().readByteArray());
                }
            }
        }
        return parts;
    }

    /**
     * Имя части из {@code Content-Disposition}: {@code form-data; name="file"; filename="upload.bin"} → {@code file}.
     */
    private static String partName(String contentDisposition) {
        Matcher matcher = PART_NAME.matcher(contentDisposition);
        assertThat(matcher.find()).as(contentDisposition).isTrue();
        return matcher.group(1);
    }

    /**
     * Тело с неизвестной длиной: OkHttp отправит его с {@code Transfer-Encoding: chunked}.
     */
    private static RequestBody streamingBody(byte[] content) {
        return new RequestBody() {
            @Override
            public okhttp3.MediaType contentType() {
                return OCTET_STREAM;
            }

            @Override
            public long contentLength() {
                return -1;
            }

            @Override
            public void writeTo(@NotNull BufferedSink sink) throws IOException {
                sink.write(content);
            }
        };
    }

    /**
     * Адрес порта, на котором никто не слушает: порт занимается и сразу освобождается,
     * поэтому подключение к нему сразу получает отказ, а не ждёт таймаута.
     */
    private static String closedPortOrigin() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return "http://127.0.0.1:" + socket.getLocalPort();
        }
    }

    /**
     * Случайные, но воспроизводимые данные: перестановка байтов не спрячется за повторяющимся шаблоном.
     */
    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }

    /**
     * GET через портал без сжатия ответа: тело сравнивается с ответом бэкенда побайтово.
     */
    private Response fetch(EmbeddedPortal target, String path) throws IOException {
        Request request = new Request.Builder()
                .url(target.url(path))
                .header("Accept-Encoding", "identity")
                .build();
        return client.newCall(request).execute();
    }

    /**
     * GET через портал с Jetty {@code GzipHandler}: проверяет, что ответ сжат, и возвращает распакованное тело.
     */
    private byte[] fetchGzipped(String path) throws IOException {
        Request request = new Request.Builder()
                .url(gzipPortal.url(path))
                .header("Accept-Encoding", "gzip")
                .build();
        try (Response response = client.newCall(request).execute()) {
            assertThat(response.code()).isEqualTo(200);
            assertThat(response.header("Content-Encoding")).isEqualTo("gzip");
            try (InputStream body = new GZIPInputStream(response.body().byteStream())) {
                return body.readAllBytes();
            }
        }
    }

    /**
     * Эталон перезаписи — прежняя строковая цепочка {@code ContentRewriter}, с которой сравнивается ответ прокси.
     */
    private byte[] rewriteLegacy(byte[] body, Backend backend) {
        ContentRewriter chain = new CompoundContentRewriter(contentRewriters.toArray(new ContentRewriter[0]));
        String rewritten = chain.rewrite(MediaType.parseMediaType("text/html; charset=utf-8"),
                new String(body, UTF_8), backend, Mockito.mock(HttpServletRequest.class));
        return rewritten.getBytes(UTF_8);
    }

    private static MockResponse htmlResponse() {
        return new MockResponse().setHeader("Content-Type", "text/html; charset=utf-8");
    }

    private static byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            gzip.write(body);
        }
        return compressed.toByteArray();
    }

    /**
     * Хост портала с настройками из {@link TestBackend}; генератор URI настоящий — он нужен для перезаписи ссылок.
     */
    private static Backend createBackend(TestBackend testBackend, String origin) {
        Host host = new Host(testBackend.uri.toLowerCase(), "localhost", origin);
        host.setUri(testBackend.uri);
        host.setReplaceUrlInContent(testBackend.replaceUrlInContent);
        host.setTimeout(testBackend.timeoutMillis);
        HostsService urls = Mockito.mock(HostsService.class);
        Mockito.when(urls.getUrlGenerator()).thenReturn(new URIGenerator());
        return new Backend(host, 0, urls);
    }

    /**
     * Хосты бэкенда. Первый сегмент пути выбирает хост, а с ним режим: поток, перезапись ссылок,
     * короткий таймаут, keep-alive, загрузки или недоступный бэкенд.
     */
    private enum TestBackend {
        STREAM("FUNCSTREAM", false, 10_000),
        REWRITE("FUNCREWRITE", true, 10_000),
        SLOW("FUNCSLOW", false, 700),
        KEEP_ALIVE("FUNCKEEPALIVE", true, 10_000),
        UPLOAD("FUNCUPLOAD", false, 10_000),
        UNREACHABLE("FUNCUNREACHABLE", false, 10_000);

        private final String uri;
        private final boolean replaceUrlInContent;
        private final int timeoutMillis;

        TestBackend(String uri, boolean replaceUrlInContent, int timeoutMillis) {
            this.uri = uri;
            this.replaceUrlInContent = replaceUrlInContent;
            this.timeoutMillis = timeoutMillis;
        }
    }

    /**
     * Выбирает ответ по последнему сегменту пути и записывает полученные запросы:
     * имя с номером запроса на соединении — для проверки keep-alive, сам запрос — для проверки тела загрузки.
     */
    private static final class RoutingDispatcher extends Dispatcher {
        private final Map<String, Supplier<MockResponse>> responses;
        private final Queue<String> requests;
        private final Map<String, RecordedRequest> recordedRequests;

        private RoutingDispatcher(Map<String, Supplier<MockResponse>> responses, Queue<String> requests,
                                  Map<String, RecordedRequest> recordedRequests) {
            this.responses = responses;
            this.requests = requests;
            this.recordedRequests = recordedRequests;
        }

        @NotNull
        @Override
        public MockResponse dispatch(@NotNull RecordedRequest recordedRequest) {
            String path = recordedRequest.getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            requests.add(name + "#" + recordedRequest.getSequenceNumber());
            recordedRequests.put(name, recordedRequest);
            Supplier<MockResponse> response = responses.get(name);
            return response != null ? response.get() : new MockResponse().setResponseCode(404);
        }
    }
}
