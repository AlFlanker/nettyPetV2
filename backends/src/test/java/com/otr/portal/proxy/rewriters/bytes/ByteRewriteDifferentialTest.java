package com.otr.portal.proxy.rewriters.bytes;

import com.otr.portal.hosts.Backend;
import com.otr.portal.proxy.rewriters.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.MediaType;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Байтовая цепочка перезаписи обязана давать ровно те же байты, что строковая цепочка на regex.
 */
class ByteRewriteDifferentialTest {
    private static final int FUZZ_CASES = 4000;
    private static final int MAX_FUZZ_TOKENS = 40;
    private static final int MAX_COMPONENT_SIZE = 7;
    private static final String[] FUZZ_TOKENS = {
            "href=", "href=", "href=", "\"", "\"", "'", "|", "/", "/", "./", "../", ".", "a", "b.css", " ",
            "\n", "\r", "\u2028", "\u2029", "\u0085", "\t", "<base", "<base ", "<base\n", ">", ">", "<a ",
            "<form", "<form ", "action=\"", "action=\"\"", "data-", "формы", "ж", "—", "«", "\u00a0",
            RewriteCorpus.BACKEND_ORIGIN + "/", RewriteCorpus.BACKEND_ORIGIN, "http://backend.local"
    };

    private final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
    private final HtmlContentRewriter html = new HtmlContentRewriter();
    private final FullLinkContentRewriter fullLink = new FullLinkContentRewriter();
    private Backend backend;

    @BeforeEach
    void setUp() {
        backend = RewriteCorpus.createBackend();
    }

    @Test
    void byteChainMatchesStringChainOnCorpus() {
        for (List<ContentRewriter> order : rewriterOrders()) {
            for (Map.Entry<String, String> corpusCase : RewriteCorpus.htmlCases().entrySet()) {
                for (String contentType : List.of("text/html; charset=utf-8", "text/css", "text/html")) {
                    assertSameAsStringChain(RewriteSample.builder()
                            .name(corpusCase.getKey())
                            .text(corpusCase.getValue())
                            .contentType(contentType)
                            .rewriters(order)
                            .build());
                }
            }
        }
    }

    @Test
    void byteChainMatchesStringChainOnRandomFragments() {
        Random random = new Random(20260930L);
        int rewrittenCases = 0;
        for (int i = 0; i < FUZZ_CASES; i++) {
            String text = createRandomFragment(random);
            for (List<ContentRewriter> order : rewriterOrders()) {
                boolean rewritten = assertSameAsStringChain(RewriteSample.builder()
                        .name("fuzz #" + i)
                        .text(text)
                        .contentType("text/html; charset=utf-8")
                        .rewriters(order)
                        .build());
                rewrittenCases += rewritten ? 1 : 0;
            }
        }
        assertThat(rewrittenCases).as("fuzz cases with actual rewrites").isGreaterThan(FUZZ_CASES / 2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"windows-1251", "ISO-8859-1", "KOI8-R"})
    void byteChainMatchesStringChainInSingleByteCharset(String charsetName) {
        Random random = new Random(charsetName.hashCode());
        List<String> texts = new ArrayList<>(RewriteCorpus.htmlCases().values());
        for (int i = 0; i < FUZZ_CASES / 4; i++) {
            texts.add(createRandomFragment(random));
        }
        for (String text : texts) {
            assertSameAsStringChain(RewriteSample.builder()
                    .name(charsetName + " " + escape(text))
                    .text(text)
                    .contentType("text/html; charset=" + charsetName)
                    .rewriters(List.of(html, fullLink))
                    .build());
        }
    }

    @Test
    void multiByteCharsetUsesStringChain() {
        RewriteSample sample = RewriteSample.builder()
                .name("utf-16")
                .text(RewriteCorpus.BASE_PAGE)
                .contentType("text/html; charset=UTF-16")
                .rewriters(List.of(html, fullLink))
                .build();

        assertThat(createBodyRewriter(sample).describeMode(sample.mediaType())).isEqualTo("rewrite:legacy");
        assertSameAsStringChain(sample);
    }

    @Test
    void rewriterWithoutByteImplementationUsesStringChain() {
        ContentRewriter upperCase = (contentType, body, backend, req) -> body.toUpperCase();
        RewriteSample sample = RewriteSample.builder()
                .name("custom rewriter")
                .text(RewriteCorpus.BASE_PAGE)
                .contentType("text/html; charset=utf-8")
                .rewriters(List.of(html, upperCase))
                .build();

        assertThat(createBodyRewriter(sample).describeMode(sample.mediaType())).isEqualTo("rewrite:legacy");
        assertSameAsStringChain(sample);
    }

    @Test
    void legacyFlagForcesStringChain() {
        RewriteSample sample = RewriteSample.builder()
                .name("legacy flag")
                .text(RewriteCorpus.BASE_PAGE)
                .contentType("text/html; charset=utf-8")
                .rewriters(List.of(html, fullLink))
                .build();
        ResponseBodyRewriter bodyRewriter = new ResponseBodyRewriter(sample.getRewriters(), () -> true);

        assertThat(bodyRewriter.describeMode(sample.mediaType())).isEqualTo("rewrite:legacy");
        assertThat(createBodyRewriter(sample).describeMode(sample.mediaType())).isEqualTo("rewrite:bytes");
    }

    @Test
    void unchangedBodyIsReturnedWithoutCopy() {
        TrackingAllocator allocator = new TrackingAllocator();
        ByteBuf source = allocator.splitIntoComponents("<p>без ссылок</p>".getBytes(StandardCharsets.UTF_8), MAX_COMPONENT_SIZE);
        RewriteSample sample = RewriteSample.builder()
                .name("no links")
                .text("")
                .contentType("text/html; charset=utf-8")
                .rewriters(List.of(html, fullLink))
                .build();

        ByteBuf rewritten = createBodyRewriter(sample).rewrite(createRequest(sample, source, allocator));

        assertThat(rewritten).isSameAs(source);
        rewritten.release();
        assertThat(allocator.findUnreleased()).isEmpty();
    }

    /**
     * @return {@code true}, если перезапись изменила тело
     */
    private boolean assertSameAsStringChain(RewriteSample sample) {
        Charset charset = sample.charset();
        byte[] input = sample.getText().getBytes(charset);
        String expectedText = new CompoundContentRewriter(sample.getRewriters().toArray(new ContentRewriter[0]))
                .rewrite(sample.mediaType(), new String(input, charset), backend, request);
        byte[] expected = expectedText.getBytes(charset);

        TrackingAllocator allocator = new TrackingAllocator();
        ByteBuf rewritten = createBodyRewriter(sample)
                .rewrite(createRequest(sample, allocator.splitIntoComponents(input, MAX_COMPONENT_SIZE), allocator));
        byte[] actual = ByteBufUtil.getBytes(rewritten);
        rewritten.release();

        assertThat(new String(actual, charset))
                .as("%s [%s] %s", sample.getName(), sample.getContentType(), escape(sample.getText()))
                .isEqualTo(new String(expected, charset));
        assertThat(actual).as(sample.getName()).isEqualTo(expected);
        assertThat(allocator.findUnreleased()).as("unreleased buffers: " + sample.getName()).isEmpty();
        return !Arrays.equals(input, expected);
    }

    private ResponseBodyRewriter createBodyRewriter(RewriteSample sample) {
        return new ResponseBodyRewriter(sample.getRewriters(), () -> false);
    }

    private BodyRewriteRequest createRequest(RewriteSample sample, ByteBuf body, TrackingAllocator allocator) {
        return BodyRewriteRequest.builder()
                .body(body)
                .contentType(sample.mediaType())
                .backend(backend)
                .request(request)
                .allocator(allocator)
                .build();
    }

    private List<List<ContentRewriter>> rewriterOrders() {
        return List.of(List.of(html, fullLink), List.of(fullLink, html));
    }

    private static String createRandomFragment(Random random) {
        StringBuilder text = new StringBuilder();
        int tokens = 1 + random.nextInt(MAX_FUZZ_TOKENS);
        for (int i = 0; i < tokens; i++) {
            text.append(FUZZ_TOKENS[random.nextInt(FUZZ_TOKENS.length)]);
        }
        return text.toString();
    }

    private static String escape(String text) {
        return text.replace("\n", "\\n").replace("\r", "\\r")
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029").replace("\u0085", "\\u0085");
    }

    static final class RewriteSample {
        private final String name;
        private final String text;
        private final String contentType;
        private final List<ContentRewriter> rewriters;

        private RewriteSample(Builder builder) {
            this.name = builder.name;
            this.text = builder.text;
            this.contentType = builder.contentType;
            this.rewriters = builder.rewriters;
        }

        static Builder builder() {
            return new Builder();
        }

        String getName() {
            return name;
        }

        String getText() {
            return text;
        }

        String getContentType() {
            return contentType;
        }

        List<ContentRewriter> getRewriters() {
            return rewriters;
        }

        MediaType mediaType() {
            return MediaType.parseMediaType(contentType);
        }

        Charset charset() {
            Charset charset = mediaType().getCharset();
            return charset != null ? charset : StandardCharsets.UTF_8;
        }

        static final class Builder {
            private String name;
            private String text;
            private String contentType;
            private List<ContentRewriter> rewriters;

            Builder name(String name) {
                this.name = name;
                return this;
            }

            Builder text(String text) {
                this.text = text;
                return this;
            }

            Builder contentType(String contentType) {
                this.contentType = contentType;
                return this;
            }

            Builder rewriters(List<ContentRewriter> rewriters) {
                this.rewriters = rewriters;
                return this;
            }

            RewriteSample build() {
                return new RewriteSample(this);
            }
        }
    }
}
