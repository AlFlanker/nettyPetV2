package com.otr.portal.proxy.rewriters;

import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.Host;
import com.otr.portal.hosts.HostsService;
import com.otr.portal.hosts.URIGenerator;
import com.otr.portal.proxy.memory.EmulatorPage;
import org.mockito.Mockito;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Корпус HTML-фрагментов с особенностями семантики regex из {@link HtmlContentRewriter}
 * и литеральных замен из {@link FullLinkContentRewriter}.
 */
public final class RewriteCorpus {
    public static final String BACKEND_ORIGIN = "http://backend.local:8080";

    public static final String BASE_PAGE = "<!doctype html>\n" +
            "<html lang=\"ru\">\n" +
            "    <head>\n" +
            "        <base href=\"/report/pages/main/\">\n" +
            "        <link rel=\"icon\" href=\"./favicon.ico\"/>\n" +
            "        <link href=\"../static/css/main.304b7f60.chunk.css\" rel=\"stylesheet\">\n" +
            "        <link href=\"" + BACKEND_ORIGIN + "/static/css/main.css\" rel=\"stylesheet\">\n" +
            "    </head>\n" +
            "    <body><a href=\"/index.zul\">Главная</a></body>\n" +
            "</html>\n";

    private RewriteCorpus() {
    }

    public static Backend createBackend() {
        Host host = new Host("golden", "localhost", BACKEND_ORIGIN);
        host.setUri("prefix");
        HostsService hostsService = Mockito.mock(HostsService.class);
        Mockito.when(hostsService.getUrlGenerator()).thenReturn(new URIGenerator());
        return new Backend(host, 1313, hostsService);
    }

    public static Map<String, String> htmlCases() {
        Map<String, String> cases = new LinkedHashMap<>();
        cases.put("absolute href", "<a href=\"/images/icon.png\">Some text</a>");
        cases.put("single quotes", "<link href='/a.css'/>");
        cases.put("pipe as quote", "<a href=|/a|>");
        cases.put("data-href", "<div data-href=\"/x\">");
        cases.put("href root consumes next attribute", "<a href=\"/\">home</a><a href=\"/x\">x</a>");
        cases.put("line feed inside value", "<a href=\"/a\nb\">");
        cases.put("carriage return inside value", "<a href=\"/a\rb\">");
        cases.put("line separator inside value", "<a href=\"/a\u2028b\">");
        cases.put("next line inside value", "<a href=\"/a\u0085b\">");
        cases.put("cyrillic path", "<a href=\"/путь/файл.png\">");
        cases.put("empty after slash", "<a href=\"/\"'>");
        cases.put("relative dot", "<a href=\"./a\">");
        cases.put("relative dot dot", "<a href='../a'>");
        cases.put("relative three dots", "<a href=\".../a\">");
        cases.put("relative without tail", "<a href=\"./\">");
        cases.put("base page", BASE_PAGE);
        cases.put("base without closing bracket", "<base href=\"/b/\"\n<a href=\"./a\">");
        cases.put("base split by whitespace lines", "<base \n\t href=\"/b/\">\n<a href=\"./a\">");
        cases.put("form single line", "<form name\"testform\" action=\"/submit\" method=\"POST\">");
        cases.put("form multi line", "<form name=\"saveDoc\" method=\"POST\"\n        class=\"zul-form\"\n"
                + "        action=\"/submit/document\">");
        cases.put("form empty action falls to formaction", "<form action=\"\"><input formaction=\"/x\"></form>");
        cases.put("form data-action", "<form data-action=\"/a\" action=\"/b\">");
        cases.put("two forms", "<form action=\"/a\"></form><form action=\"/b\">");
        cases.put("formaction glued to tag", "<formaction=\"/a\">");
        cases.put("form without closing bracket", "<form action=\"/a\"");
        cases.put("full backend link", "<script src=\"" + BACKEND_ORIGIN + "/zk.js\"></script>");
        cases.put("full backend link in href", "<link href=\"" + BACKEND_ORIGIN + "/a.css\">");
        cases.put("emulator page", EmulatorPage.buildHtml(64 * 1024, BACKEND_ORIGIN));
        return cases;
    }
}
