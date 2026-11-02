package example.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/**
 * Принимает запрос, проверяет допустимость операции и резервирует место среди
 * активных обменов. Дальнейшей передачей и освобождением ресурсов владеет ProxyCommand.
 */
final class ProxyServlet extends HttpServlet {
    private final ProxyTransport transport;

    ProxyServlet(ProxyTransport transport) { this.transport = transport; }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (request.getDispatcherType() == DispatcherType.ERROR) {
            // Внутренняя обработка ошибки контейнером не является новым запросом
            // клиента. Повторное проксирование здесь могло бы отправить /error на backend.
            Object status = request.getAttribute("jakarta.servlet.error.status_code");
            reject(response, status instanceof Integer code ? code : 500);
            return;
        }
        if (request.getDispatcherType() != DispatcherType.REQUEST) {
            reject(response, 500);
            return;
        }
        if ("CONNECT".equals(request.getMethod()) || request.getHeader("Upgrade") != null
                || request.getHeader("Trailer") != null) {
            // Пример передаёт обычные HTTP-сообщения. Туннели, смена протокола
            // и объявленные трейлеры требуют отдельной реализации.
            reject(response, 501);
            return;
        }
        // Не создаём неограниченную очередь ожидающих запросов: при насыщении
        // сразу отвечаем 503, сохраняя предел числа рабочих задач и каналов.
        if (!transport.inFlight.tryAcquire()) {
            reject(response, 503);
            return;
        }
        final AsyncContext async;
        try {
            async = request.startAsync();
            async.setTimeout(0); // Общий срок обмена контролирует ProxyCommand; второй таймер не нужен.
        } catch (RuntimeException error) {
            transport.inFlight.release();
            throw error;
        }
        ProxyCommand command = new ProxyCommand(request, response, async, transport);
        async.addListener(new AsyncListener() {
            @Override public void onComplete(AsyncEvent event) { command.onServletComplete(); }
            @Override public void onTimeout(AsyncEvent event) {
                command.cancel(new IOException("Servlet async timeout"));
            }
            @Override public void onError(AsyncEvent event) {
                command.onServletError(event.getThrowable() == null ? new IOException("Servlet async error") : event.getThrowable());
            }
            @Override public void onStartAsync(AsyncEvent event) { }
        });
        // Место в семафоре остаётся занятым до фактической остановки I/O-задач,
        // даже если клиент уже отключился или получил ответ об ошибке.
        command.start();
    }

    private static void reject(HttpServletResponse response, int status) {
        // sendError мог бы запустить внутреннюю диспетчеризацию ошибки Spring.
        // Формируем окончательный пустой ответ непосредственно и закрываем соединение:
        // непрочитанное тело отвергнутого запроса не должно стать следующим запросом.
        if (!response.isCommitted()) {
            response.setStatus(status);
            response.setHeader("Connection", "close");
            response.setContentLength(0);
        }
    }
}
