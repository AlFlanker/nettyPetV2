package com.otr.portal.proxy.rewriters;

import com.otr.portal.hosts.Backend;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Эталон поведения строковой цепочки {@link ContentRewriter}: побайтовая реализация обязана совпадать с ним.
 */
class ContentRewriterGoldenTest {
    private static final MediaType HTML = MediaType.parseMediaType("text/html; charset=utf-8");
    private static final MediaType CSS = MediaType.parseMediaType("text/css; charset=utf-8");

    private final HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
    private final HtmlContentRewriter html = new HtmlContentRewriter();
    private final FullLinkContentRewriter fullLink = new FullLinkContentRewriter();
    private Backend backend;
    private Map<String, String> cases;

    @BeforeEach
    void setUp() {
        backend = RewriteCorpus.createBackend();
        cases = RewriteCorpus.htmlCases();
    }

    @Test
    void rewritesAbsoluteHrefWithAnyQuote() {
        assertHtml("absolute href", "<a href=\"/prefix/images/icon.png\">Some text</a>");
        assertHtml("single quotes", "<link href=\"/prefix/a.css\"/>");
        assertHtml("pipe as quote", "<a href=\"/prefix/a\">");
        assertHtml("data-href", "<div data-href=\"/prefix/x\">");
        assertHtml("cyrillic path", "<a href=\"/prefix/путь/файл.png\">");
    }

    @Test
    void lazyValueStopsAtFirstQuoteAfterOneChar() {
        assertHtml("href root consumes next attribute", "<a href=\"/prefix/\">home</a><a href=\"/x\">x</a>");
        assertHtml("empty after slash", "<a href=\"/prefix/\"\">");
    }

    @Test
    void valueNeverCrossesLineTerminator() {
        assertHtmlUnchanged("line feed inside value");
        assertHtmlUnchanged("carriage return inside value");
        assertHtmlUnchanged("line separator inside value");
        assertHtmlUnchanged("next line inside value");
    }

    @Test
    void rewritesRelativeHrefOnlyWithoutBaseTag() {
        assertHtml("relative dot", "<a href=\"/prefix/./a\">");
        assertHtml("relative dot dot", "<a href=\"/prefix/../a\">");
        assertHtmlUnchanged("relative three dots");
        assertHtmlUnchanged("relative without tail");
        assertHtml("base without closing bracket", "<base href=\"/prefix/b/\"\n<a href=\"/prefix/./a\">");
        assertHtml("base split by whitespace lines", "<base \n\t href=\"/prefix/b/\">\n<a href=\"./a\">");
        assertHtml("base page", RewriteCorpus.BASE_PAGE
                .replace("href=\"/report/pages/main/\"", "href=\"/prefix/report/pages/main/\"")
                .replace("href=\"/index.zul\"", "href=\"/prefix/index.zul\""));
    }

    @Test
    void rewritesFirstFormActionUpToClosingBracket() {
        assertHtml("form single line", "<form name\"testform\" action=\"/prefix/submit\" method=\"POST\">");
        assertHtml("form multi line", "<form name=\"saveDoc\" method=\"POST\"\n        class=\"zul-form\"\n"
                + "        action=\"/prefix/submit/document\">");
        assertHtml("form empty action falls to formaction", "<form action=\"\"><input formaction=\"/prefix/x\"></form>");
        assertHtml("form data-action", "<form data-action=\"/prefix/a\" action=\"/b\">");
        assertHtml("two forms", "<form action=\"/prefix/a\"></form><form action=\"/prefix/b\">");
        assertHtmlUnchanged("formaction glued to tag");
        assertHtmlUnchanged("form without closing bracket");
    }

    @Test
    void appliesHtmlRulesOnlyToHtmlSubtype() {
        String css = ".a { background: url('/x.png'); } <a href=\"/x\">";

        assertThat(html.rewrite(CSS, css, backend, request)).isEqualTo(css);
    }

    @Test
    void replacesFullBackendUrlInAnyText() {
        assertThat(fullLink.rewrite(HTML, cases.get("full backend link"), backend, request))
                .isEqualTo("<script src=\"/prefix/zk.js\"></script>");
        assertThat(fullLink.rewrite(CSS, "url('" + RewriteCorpus.BACKEND_ORIGIN + "/a.png')", backend, request))
                .isEqualTo("url('/prefix/a.png')");
    }

    @Test
    void chainResultDependsOnRewriterOrder() {
        String input = cases.get("full backend link in href");

        assertThat(new CompoundContentRewriter(html, fullLink).rewrite(HTML, input, backend, request))
                .isEqualTo("<link href=\"/prefix/a.css\">");
        assertThat(new CompoundContentRewriter(fullLink, html).rewrite(HTML, input, backend, request))
                .isEqualTo("<link href=\"/prefix/prefix/a.css\">");
    }

    private void assertHtml(String caseName, String expected) {
        assertThat(html.rewrite(HTML, cases.get(caseName), backend, request))
                .as(caseName)
                .isEqualTo(expected);
    }

    private void assertHtmlUnchanged(String caseName) {
        assertHtml(caseName, cases.get(caseName));
    }
}
