package com.otr.portal.proxy;

import com.otr.portal.core.model.http.Header;
import com.otr.portal.hosts.Backend;
import com.otr.portal.proxy.ProxyCommandSupport.DataToSend;
import com.otr.portal.proxy.headers.HeaderProcessor;
import com.otr.portal.proxy.logging.ProxiedRequestLifecycle;
import com.otr.portal.proxy.memory.NettyLeakRecorder;
import com.otr.portal.proxy.memory.UrlEncodedForm;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import jakarta.servlet.ServletInputStream;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static com.otr.portal.proxy.RequestBodyReader.CHUNK_SIZE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Владение телом запроса в {@link ProxyCommandSupport#buildFullHttpRequest}: тело в pooled direct-памяти
 * либо уходит в запрос для Netty, либо освобождается - при ошибке и когда encoder пересобирает форму по частям.
 * <p>
 * Pooled direct-буфер не освобождает GC: забытый {@code release()} - это утечка памяти пула, которая на стенде
 * видна как рост direct-памяти вплоть до {@code OutOfDirectMemoryError}. Поэтому каждый путь проверяется
 * по {@code refCnt()} или детектором утечек Netty (в тестах модуля он работает в режиме paranoid).
 * Заголовки, бэкенд и журнал запроса замоканы: проверяется только сборка тела.
 */
class ProxyCommandSupportTest {
    /**
     * Метод, где создаётся тело. По нему отбираются отчёты детектора, чтобы не учитывать чужие буферы.
     */
    private static final String ALLOCATION_SITE = "ProxyCommandSupport.buildFullHttpRequest";
    /**
     * Порядок порога {@code HttpPostRequestEncoder} (8096 байт), выше которого форма уходит по частям.
     */
    private static final int ENCODER_CHUNK_THRESHOLD = 8 * 1024;

    private final HeaderProcessor headerProcessor = mock(HeaderProcessor.class);
    private final Backend backend = mock(Backend.class);
    private final ProxiedRequestLifecycle lifecycle = mock(ProxiedRequestLifecycle.class);
    private NettyLeakRecorder leakRecorder;

    /**
     * Заголовки на тело не влияют, поэтому процессор возвращает пустой поток.
     * Регистратор утечек свой на каждый тест, чтобы отчёты разных тестов не смешивались.
     */
    @BeforeEach
    void startLeakRecorder() {
        when(headerProcessor.collectHeaders(any(), any())).thenAnswer(invocation -> Stream.empty());
        leakRecorder = NettyLeakRecorder.start();
    }

    @AfterEach
    void stopLeakRecorder() {
        leakRecorder.close();
    }

    /**
     * Обычное тело (PUT с бинарными данными) лежит целиком в direct-кусках, дошло без изменений,
     * получило {@code Content-Length} и освобождается через {@link DataToSend#release()}.
     * Кусков 21 - больше 16, лимита компонентов {@code CompositeByteBuf} по умолчанию: при этом лимите Netty
     * склеил бы куски в один буфер, скопировав всё тело, и тест это поймает.
     */
    @Test
    void readsRawBodyIntoDirectChunks() throws Exception {
        byte[] data = randomBytes(20 * CHUNK_SIZE + 7);

        DataToSend request = build(upload(data));

        FullHttpRequest sent = (FullHttpRequest) request.getHttpRequest();
        try {
            assertThat(request.isChunked()).isFalse();
            assertThat(sent.content().isDirect()).isTrue();
            assertThat(((CompositeByteBuf) sent.content()).numComponents())
                    .as("куски не склеиваются при превышении 16 компонентов по умолчанию")
                    .isEqualTo(21);
            assertThat(ByteBufUtil.getBytes(sent.content())).isEqualTo(data);
            assertThat(sent.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo(String.valueOf(data.length));
        } finally {
            request.release();
        }
        assertThat(sent.refCnt()).isZero();
    }

    /**
     * Без {@code Content-Length} и {@code Transfer-Encoding} тела у HTTP-запроса нет, даже если в потоке есть байты:
     * поток не читается, тело пустое, {@code Content-Length} не добавляется.
     * Иначе GET-запросы ждали бы чтения потока и уходили бы в бэкенд с лишним заголовком.
     */
    @Test
    void skipsBodyWithoutLengthHeaders() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/page");
        req.setContent(new byte[]{1, 2, 3});

        DataToSend request = build(req);

        FullHttpRequest sent = (FullHttpRequest) request.getHttpRequest();
        try {
            assertThat(sent.content().readableBytes()).isZero();
            assertThat(sent.headers().contains(HttpHeaderNames.CONTENT_LENGTH)).isFalse();
        } finally {
            request.release();
        }
    }

    /**
     * Форму, которую до прокси никто не читал, прокси передаёт побайтово: поток прочитан им самим, и параметров
     * из тела после этого нет (Jetty их уже не разбирает, в моке их не задано). Encoder не нужен, тело и длина исходные.
     * PUT - случай из HAR, когда {@code FormContentFilter} больше не вычитывает тело; POST - форма без пересборки.
     */
    @ParameterizedTest
    @ValueSource(strings = {"PUT", "POST"})
    void forwardsUnreadFormAsIs(String method) throws Exception {
        byte[] form = ("content=" + URLEncoder.encode("{\"id\":\"значение\"}", UTF_8)).getBytes(UTF_8);
        MockHttpServletRequest req = new MockHttpServletRequest(method, "/form");
        req.setContentType("application/x-www-form-urlencoded; charset=UTF-8");
        req.setContent(form);
        req.addHeader(HttpHeaders.CONTENT_LENGTH, form.length);

        DataToSend request = build(req);

        FullHttpRequest sent = (FullHttpRequest) request.getHttpRequest();
        try {
            assertThat(request.getEncoder()).isNull();
            assertThat(ByteBufUtil.getBytes(sent.content())).isEqualTo(form);
            assertThat(sent.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isEqualTo(String.valueOf(form.length));
        } finally {
            request.release();
        }
    }

    /**
     * Тело прочитали до прокси, а клиентский {@code Content-Length} пришёл в заголовках: длина выставляется по факту.
     * С заявленной длиной бэкенд ждал бы недостающие байты до таймаута - так PUT-форма получала 504.
     */
    @Test
    void replacesStaleContentLengthWhenBodyAlreadyRead() throws Exception {
        when(headerProcessor.collectHeaders(any(), any()))
                .thenAnswer(invocation -> Stream.of(new Header(HttpHeaders.CONTENT_LENGTH, "51128")));
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/form");
        req.setContentType("application/x-www-form-urlencoded");
        req.addHeader(HttpHeaders.CONTENT_LENGTH, 51128);

        DataToSend request = build(req);

        FullHttpRequest sent = (FullHttpRequest) request.getHttpRequest();
        try {
            assertThat(sent.content().readableBytes()).isZero();
            assertThat(sent.headers().getAll(HttpHeaderNames.CONTENT_LENGTH)).containsExactly("0");
        } finally {
            request.release();
        }
    }

    /**
     * Клиент оборвал загрузку после трёх с лишним кусков: исключение пробрасывается,
     * а уже прочитанные куски освобождаются в {@code catch}. Запрос из метода не вернулся,
     * сослаться на буфер нельзя, поэтому проверка - по детектору утечек.
     */
    @Test
    void releasesBodyWhenClientAbortsUpload() throws Exception {
        MockHttpServletRequest req = new AbortedUploadRequest(randomBytes(3 * CHUNK_SIZE + 100));

        assertThatThrownBy(() -> build(req)).isInstanceOf(IOException.class);

        assertThat(leakRecorder.collectLeaksCreatedIn(ALLOCATION_SITE)).isEmpty();
    }

    /**
     * Параметры маленькой формы уже прочитаны фильтром, поэтому encoder пересобирает тело в тот же direct-буфер.
     * Запрос не chunked, форма совпадает с исходной (с кириллицей), {@code Content-Length} равен длине
     * пересобранного тела, после освобождения буфер возвращён в пул.
     * Ловит рассинхрон длины после пересборки и замену тела на heap-буфер.
     */
    @Test
    void writesSmallFormIntoBodyBuffer() throws Exception {
        MockHttpServletRequest req = form(Map.of("a", "1", "name", "значение"));

        DataToSend request = build(req);

        FullHttpRequest sent = (FullHttpRequest) request.getHttpRequest();
        try {
            assertThat(request.isChunked()).isFalse();
            assertThat(sent.content().isDirect()).isTrue();
            assertThat(UrlEncodedForm.parse(sent.content().toString(UTF_8)))
                    .isEqualTo(Map.of("a", List.of("1"), "name", List.of("значение")));
            assertThat(sent.headers().get(HttpHeaderNames.CONTENT_LENGTH))
                    .isEqualTo(String.valueOf(sent.content().readableBytes()));
        } finally {
            request.release();
        }
        assertThat(sent.refCnt()).isZero();
    }

    /**
     * Для формы больше 8 КиБ encoder отдаёт {@code WrappedHttpRequest}, который прячет исходное тело:
     * в канал оно не уходит, и освободить его должен {@code buildFullHttpRequest}.
     * Исходный запрос доступен только через поле encoder'а: пока он достижим, детектор утечек его не увидит,
     * поэтому проверяется {@code refCnt()}. Вторая проверка - части encoder'а складываются в исходную форму.
     */
    @Test
    void releasesOriginalBodyWhenEncoderSendsChunks() throws Exception {
        String value = "значение ".repeat(ENCODER_CHUNK_THRESHOLD / 16);

        DataToSend request = build(form(Map.of("big", value)));
        try {
            assertThat(request.isChunked()).isTrue();
            FullHttpRequest original = (FullHttpRequest) FieldUtils.readField(request.getEncoder(), "request", true);
            assertThat(original.refCnt()).isZero();
            assertThat(UrlEncodedForm.parse(readChunks(request.getEncoder())))
                    .isEqualTo(Map.of("big", List.of(value)));
        } finally {
            request.release();
        }
    }

    /**
     * Собирает запрос без keep-alive и без обогащения заголовков: оба влияют только на заголовки, не на тело.
     */
    private DataToSend build(MockHttpServletRequest req) throws Exception {
        return ProxyCommandSupport.buildFullHttpRequest(
                req, req.getRequestURI(), backend, headerProcessor, List.of(), false, lifecycle);
    }

    /**
     * PUT с бинарным телом и {@code Content-Length}, как загрузка файла без multipart.
     */
    private static MockHttpServletRequest upload(byte[] data) {
        MockHttpServletRequest req = new MockHttpServletRequest("PUT", "/upload");
        req.setContentType("application/octet-stream");
        req.setContent(data);
        req.addHeader(HttpHeaders.CONTENT_LENGTH, data.length);
        return req;
    }

    /**
     * Тело формы и уже разобранные параметры - как после фильтра, прочитавшего параметры до прокси.
     */
    private static MockHttpServletRequest form(Map<String, String> params) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/form");
        req.setContentType("application/x-www-form-urlencoded");
        req.setCharacterEncoding(UTF_8.name());
        StringBuilder body = new StringBuilder();
        params.forEach((name, value) -> {
            req.addParameter(name, value);
            body.append(body.length() == 0 ? "" : "&")
                    .append(URLEncoder.encode(name, UTF_8)).append('=').append(URLEncoder.encode(value, UTF_8));
        });
        byte[] content = body.toString().getBytes(UTF_8);
        req.setContent(content);
        req.addHeader(HttpHeaders.CONTENT_LENGTH, content.length);
        return req;
    }

    /**
     * Вычитывает все части encoder'а, как {@code ChunkedWriteHandler} при записи в канал, и освобождает каждую.
     */
    private static String readChunks(HttpPostRequestEncoder encoder) throws Exception {
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        while (!encoder.isEndOfInput()) {
            HttpContent chunk = encoder.readChunk(ByteBufAllocator.DEFAULT);
            try {
                chunk.content().readBytes(sent, chunk.content().readableBytes());
            } finally {
                chunk.release();
            }
        }
        return sent.toString(UTF_8);
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
     * Заявляет тело на байт длиннее полученного и обрывает его, как клиент, закрывший соединение во время загрузки.
     */
    private static final class AbortedUploadRequest extends MockHttpServletRequest {
        private final byte[] received;

        private AbortedUploadRequest(byte[] received) {
            super("PUT", "/upload");
            this.received = received;
            addHeader(HttpHeaders.CONTENT_LENGTH, received.length + 1);
        }

        @Override
        public ServletInputStream getInputStream() {
            return new DelegatingServletInputStream(new AbortedInputStream(received));
        }
    }
}
