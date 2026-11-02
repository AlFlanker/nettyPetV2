package com.otr.portal.proxy.rewriters;

import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.Host;
import com.otr.portal.hosts.HostsService;
import com.otr.portal.hosts.URIGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;

import jakarta.servlet.http.HttpServletRequest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author pobedenniy.alexey
 * @since 20.08.18
 */
public class HtmlContentRewriterTest {
    private HtmlContentRewriter rewriter = new HtmlContentRewriter();
    private Backend backend;

    @BeforeEach
    public void setUp() throws Exception {
        final Host host = new Host();
        host.setUri("prefix");
        final HostsService mock = Mockito.mock(HostsService.class);
        Mockito.when(mock.getUrlGenerator()).thenReturn(new URIGenerator());
        backend = new Backend(host, 1313, mock);
    }

    @Test
    public void rewritesHref() {
        String result = rewriter.rewrite(MediaType.TEXT_HTML, "<a href=\"/images/icon.png\">Some text</a>", backend, Mockito.mock(HttpServletRequest.class));
        assertThat(result, is("<a href=\"/prefix/images/icon.png\">Some text</a>"));
    }

    @Test
    public void rewritesRelativeHref() {
        String resultUp = rewriter.rewrite(MediaType.TEXT_HTML, "<a href=\"../images/icon.png\">Some text</a>", backend, Mockito.mock(HttpServletRequest.class));
        String resultRelative = rewriter.rewrite(MediaType.TEXT_HTML, "<a href=\"./images/icon.png\">Some text</a>", backend, Mockito.mock(HttpServletRequest.class));

        assertThat(resultUp, is("<a href=\"/prefix/../images/icon.png\">Some text</a>"));
        assertThat(resultRelative, is("<a href=\"/prefix/./images/icon.png\">Some text</a>"));
    }

    @Test
    public void rewritesFormAction() {
        String formAction = rewriter.rewrite(MediaType.TEXT_HTML, "<form name\"testform\" action=\"/submit\" method=\"POST\">", backend, Mockito.mock(HttpServletRequest.class));
        assertThat(formAction, is("<form name\"testform\" action=\"/prefix/submit\" method=\"POST\">"));

    }

    @Test
    public void doNotRewritesIfBaseTagExists() {
        String htmlPage = "<!doctype html>\n" +
                "<html lang=\"ru\">\n" +
                "    <head>\n" +
                "        <base href=\"/report/pages/main/\">\n" +
                "        <link rel=\"icon\" href=\"./favicon.ico\"/>\n" +
                "        <link rel=\"apple-touch-icon\" href=\"./logo192.png\"/>\n" +
                "        <link href=\"./static/css/2.a95be28f.chunk.css\" rel=\"stylesheet\">\n" +
                "        <link href=\"../static/css/main.304b7f60.chunk.css\" rel=\"stylesheet\">\n" +
                "        <link href=\"http://test.otr.ru/static/css/main.304b7f60.chunk.css\" rel=\"stylesheet\">\n" +
                "    </head>\n" +
                "    <body>\n" +
                "        <script src=\"./static/js/2.a6c251c3.chunk.js\"></script>\n" +
                "        <script src=\"./static/js/main.ed73cfa0.chunk.js\"></script>\n" +
                "    </body>\n" +
                "</html>\n";
        String htmlPageTest = "<!doctype html>\n" +
                "<html lang=\"ru\">\n" +
                "    <head>\n" +
                "        <base href=\"" + backend.fullFrontendUrl("") + "/report/pages/main/\">\n" +
                "        <link rel=\"icon\" href=\"./favicon.ico\"/>\n" +
                "        <link rel=\"apple-touch-icon\" href=\"./logo192.png\"/>\n" +
                "        <link href=\"./static/css/2.a95be28f.chunk.css\" rel=\"stylesheet\">\n" +
                "        <link href=\"../static/css/main.304b7f60.chunk.css\" rel=\"stylesheet\">\n" +
                "        <link href=\"http://test.otr.ru/static/css/main.304b7f60.chunk.css\" rel=\"stylesheet\">\n" +
                "    </head>\n" +
                "    <body>\n" +
                "        <script src=\"./static/js/2.a6c251c3.chunk.js\"></script>\n" +
                "        <script src=\"./static/js/main.ed73cfa0.chunk.js\"></script>\n" +
                "    </body>\n" +
                "</html>\n";

        String resultRewriter = rewriter.rewrite(MediaType.TEXT_HTML, htmlPage,
                backend, Mockito.mock(HttpServletRequest.class));
        assertThat(resultRewriter, is(htmlPageTest));
    }

}
