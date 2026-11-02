package com.otr.portal.proxy.memory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Порт страницы эмулятора {@code solutions/fk-portal/research/rewrite-backend/server.py}:
 * ZK-страница заданного размера с ~40 ссылками для перезаписи и кириллицей в теле.
 */
public final class EmulatorPage {
    public static final String OAM_ANTI_IFRAME = "if (self==top) {\n" +
            "  document.documentElement.style.visibility='visible';\n" +
            "} else {\n" +
            "  top.location=self.location;\n" +
            "}";

    private static final List<String> STYLES = List.of(
            "/static/resources/css/zk.wcs",
            "/static/resources/css/norm.wcs",
            "/static/css/theme.css",
            "/static/css/grid.css",
            "/static/css/print.css",
            "/sufdclient/zkau/web/css/zk.wcs",
            "/versioned/css/app.css",
            "/zkau/web/zul/css/zk.css.dsp"
    );
    private static final List<String> REL_STYLES = List.of(
            "./static/css/main.css",
            "./favicon.ico",
            "../static/css/chunk.css",
            "../images/logo.png"
    );
    private static final List<String> SCRIPTS_ABS = List.of(
            "/zkau/web/js/zk.wpd",
            "/zkau/web/js/zul.wnd.wpd",
            "/zkau/web/js/zul.mesh.wpd",
            "/zkau/web/js/zul.wgt.wpd",
            "/zkau/web/js/zul.sel.wpd",
            "/zkau/web/js/zul.utl.wpd",
            "/zkau/web/js/zul.inp.wpd",
            "/zkau/web/js/zul.box.wpd",
            "/zkau/web/js/zul.layout.wpd",
            "/zkau/web/js/zkmax.wpd",
            "/zkau/web/js/zkex.wpd",
            "/static/js/app.js",
            "/static/js/grid.js"
    );
    private static final List<String> SCRIPTS_REL = List.of(
            "./static/js/boot.js",
            "../zkau/web/js/zul.menu.wpd"
    );
    private static final List<String> NAV_HREFS = List.of(
            "/index.zul",
            "/scroller.zul?navigationId=root",
            "/logout.zul"
    );
    private static final String PAD_TEXT = "Наименование документа, статус согласования, сумма по бюджетной классификации " +
            "и комментарий ответственного исполнителя: абвгдеёжзийклмнопрстуфхцчшщъыьэюя. ";
    private static final String FOOTER = "    </tbody>\n  </table>\n</body>\n</html>\n";

    private EmulatorPage() {
    }

    /**
     * @param sizeBytes     целевой размер тела в байтах UTF-8
     * @param backendOrigin адрес бэкенда без завершающего слеша, как в {@code Host.target}
     */
    public static String buildHtml(int sizeBytes, String backendOrigin) {
        String header = buildHead(backendOrigin);
        StringBuilder page = new StringBuilder(sizeBytes);
        page.append(header);
        int current = utf8Length(header) + utf8Length(FOOTER);
        int rowSize = utf8Length(buildRow(0));
        for (int index = 0; current + rowSize < sizeBytes; index++) {
            String row = buildRow(index);
            page.append(row);
            current += utf8Length(row);
        }
        return page.append(FOOTER).toString();
    }

    public static String buildCss(String backendOrigin) {
        return "/* zk.wcs */\n" +
                ".z-grid { background: url('" + backendOrigin + "/static/images/bg.png'); }\n" +
                ".z-icon { background: url('/static/images/icon.png'); }\n" +
                ".z-logo { background: url('../images/logo.png'); }\n" +
                "@import url('" + backendOrigin + "/static/css/theme.css');\n" +
                "@import url('/static/css/grid.css');\n";
    }

    private static String buildHead(String origin) {
        String links = STYLES.stream().map(EmulatorPage::stylesheet).collect(Collectors.joining("\n")) + "\n" +
                REL_STYLES.stream().map(EmulatorPage::stylesheet).collect(Collectors.joining("\n")) + "\n" +
                stylesheet(origin + "/static/css/brand.css") + "\n" +
                stylesheet(origin + "/zkau/web/css/zk.wcs");
        String scripts = SCRIPTS_ABS.stream().map(EmulatorPage::script).collect(Collectors.joining("\n")) + "\n" +
                SCRIPTS_REL.stream().map(EmulatorPage::script).collect(Collectors.joining("\n")) + "\n" +
                script(origin + "/zkau/web/js/zk.wpd") + "\n" +
                script(origin + "/static/js/boot.js");
        String nav = NAV_HREFS.stream()
                .map(href -> "<a href=\"" + href + "\">" + href.split("\\?")[0] + "</a>")
                .collect(Collectors.joining(", "))
                + ", <a href=\"" + origin + "/index.zul\">полный URL index.zul</a>";
        return "<!doctype html>\n" +
                "<html lang=\"ru\">\n" +
                "<head>\n" +
                "  <meta charset=\"utf-8\"/>\n" +
                "  <title>Реестр документов</title>\n" +
                "  <script>\n" +
                OAM_ANTI_IFRAME + "\n" +
                "  </script>\n" +
                links + "\n" +
                scripts + "\n" +
                "</head>\n" +
                "<body>\n" +
                "  <h1>Реестр документов</h1>\n" +
                "  <p>Навигация: " + nav + "</p>\n" +
                "  <form name=\"search\" action=\"/search\" method=\"GET\">\n" +
                "    <input type=\"text\" name=\"q\" value=\"поиск по реестру\"/>\n" +
                "    <button type=\"submit\">Найти</button>\n" +
                "  </form>\n" +
                "  <form name=\"saveDoc\" method=\"POST\"\n" +
                "        class=\"zul-form\"\n" +
                "        action=\"/submit/document\">\n" +
                "    <input type=\"hidden\" name=\"guid\" value=\"demo\"/>\n" +
                "    <button type=\"submit\">Сохранить</button>\n" +
                "  </form>\n" +
                "  <table class=\"zk-grid\">\n" +
                "    <thead><tr><th>№</th><th>Документ</th><th>Комментарий</th></tr></thead>\n" +
                "    <tbody>\n";
    }

    private static String stylesheet(String href) {
        return "  <link rel=\"stylesheet\" href=\"" + href + "\"/>";
    }

    private static String script(String src) {
        return "  <script src=\"" + src + "\"></script>";
    }

    private static String buildRow(int index) {
        return "<tr data-row=\"" + index + "\">" +
                "<td>" + index + "</td>" +
                "<td>Документ №" + index + " от 10.09.2026</td>" +
                "<td>" + PAD_TEXT + "</td>" +
                "</tr>\n";
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
