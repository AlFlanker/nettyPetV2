package example.proxy;

import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Преобразует только начальную строку и заголовки Servlet-запроса в формат Netty.
 * Тело передаёт ProxyCommand: оно не разбирается на параметры формы и не собирается заново.
 */
final class ProxyCommandSupport {
    private ProxyCommandSupport() { }

    static HttpRequest requestHead(HttpServletRequest request, ProxyTransport transport) {
        // Сохраняем исходное кодирование пути и строки параметров, включая повторные
        // параметры, знак «+» и %-последовательности. URLDecoder здесь изменил бы запрос.
        String target = request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        var head = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(request.getMethod()), target);
        Set<String> excluded = hopByHop(Collections.list(request.getHeaders("Connection")));
        excluded.add("host");
        excluded.add("expect"); // Ответ 100 Continue клиенту формирует Jetty при чтении тела.
        excluded.add("content-length"); // Способ обозначения границ тела задаём ниже для нового соединения.
        for (String name : Collections.list(request.getHeaderNames())) {
            if (!excluded.contains(name.toLowerCase(Locale.ROOT))) {
                head.headers().add(name, Collections.list(request.getHeaders(name)));
            }
        }
        // Host относится к целевому серверу. Одно соединение обслуживает один обмен;
        // это также исключает повторное использование канала после неполной передачи тела.
        head.headers().set(HttpHeaderNames.HOST, transport.hostHeader);
        head.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        // Content-Length и chunked взаимоисключающие. Неизвестную длину не вычисляем
        // предварительным чтением: HttpClientCodec оформит порции как HTTP-чанки.
        long length = request.getContentLengthLong();
        if (length >= 0) {
            HttpUtil.setContentLength(head, length);
        } else if (hasBody(request)) {
            HttpUtil.setTransferEncodingChunked(head, true);
        } else {
            HttpUtil.setContentLength(head, 0);
        }
        return head;
    }

    static boolean hasBody(HttpServletRequest request) {
        // Наличие тела определяется протоколом и заголовками, а не именем метода:
        // например, GET или DELETE также могут содержать тело. Ветка HTTP/2 учитывает
        // отсутствие Transfer-Encoding у такого запроса, но приём HTTP/2 в pet не настроен.
        return request.getContentLengthLong() > 0 || request.getHeader("Transfer-Encoding") != null
                || request.getProtocol().startsWith("HTTP/2");
    }

    static Set<String> hopByHop(Collection<String> connectionHeaders) {
        // Эти заголовки описывают одно транспортное соединение и не должны переходить
        // на другое. Connection может дополнительно перечислять произвольные имена:
        // исключаем их наравне со стандартным набором в обоих направлениях передачи.
        Set<String> names = new HashSet<>(Set.of("connection", "keep-alive", "proxy-connection",
                "proxy-authenticate", "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade"));
        for (String value : connectionHeaders) {
            for (String token : value.split(",")) names.add(token.trim().toLowerCase(Locale.ROOT));
        }
        return names;
    }
}
