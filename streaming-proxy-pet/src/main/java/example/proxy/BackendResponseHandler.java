package example.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.ReferenceCountUtil;

import java.io.EOFException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Передаёт HTTP-ответ из потока Netty рабочему потоку {@link ProxyCommand}.
 * <p>
 * Предшествующий FlowControlHandler выдаёт объекты по явному запросу чтения.
 * Наша очередь вмещает один объект: следующий запрос чтения команда делает после
 * обработки предыдущего. Это ограничивает очередь между потоками, но не означает,
 * что во всём сетевом стеке одновременно существует только один буфер.
 * <p>
 * Обработчик не записывает servlet-ответ. Он передаёт сообщения и уведомляет команду
 * об ошибках; статус, заголовки и тело ответа остаются у единственного владельца.
 */
final class BackendResponseHandler extends ChannelInboundHandlerAdapter {
    private final ProxyCommand exchange;
    private final ArrayBlockingQueue<Object> received = new ArrayBlockingQueue<>(1);
    // Эти два признака изменяются только потоком Netty. complete означает получение
    // последнего объекта финального ответа, а не завершение его записи клиенту.
    private boolean finalResponse;
    private boolean complete;
    // Читается и изменяется под монитором received: завершение команды может
    // происходить одновременно с приходом последнего сетевого сообщения.
    private boolean stopped;

    BackendResponseHandler(ProxyCommand exchange) { this.exchange = exchange; }

    @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
        if (message instanceof HttpObject http && !http.decoderResult().isSuccess()) {
            // Некорректно декодированный объект потребителю не передаётся;
            // за освобождение его возможного ByteBuf отвечаем здесь.
            ReferenceCountUtil.release(message);
            exchange.cancel(http.decoderResult().cause());
            return;
        }
        if (message instanceof HttpResponse head && head.status().code() >= 200) finalResponse = true;
        // LastHttpContent после промежуточного 100 Continue ещё не завершает обмен.
        if (message instanceof LastHttpContent && finalResponse) complete = true;
        // ChannelInboundHandlerAdapter не освобождает сообщения автоматически.
        // При успешном offer очередь принимает имеющуюся ссылку без retain();
        // после next() её должен освободить потребитель. Монитор делает проверку
        // stopped и передачу владения атомарными относительно releaseQueued().
        synchronized (received) {
            if (stopped) {
                ReferenceCountUtil.release(message);
            } else if (!received.offer(message)) {
                // Поток Netty нельзя блокировать ожиданием места. Переполнение
                // означает нарушение протокола чтения: освобождаем только новый
                // объект, а уже принятый будет освобождён потребителем или при очистке.
                ReferenceCountUtil.release(message);
                exchange.cancel(new IllegalStateException("Backend demand queue exceeded"));
            }
        }
    }

    /**
     * Передаёт потребителю владение извлечённым объектом; null означает только
     * отсутствие сообщения за указанный интервал. Метод вызывается вне потока Netty.
     */
    Object next(long timeoutMillis) throws InterruptedException {
        return received.poll(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    @Override public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        // Сигнал относится к отправке запроса в том же канале. Здесь только будим
        // поток отправки: чтение ServletInputStream из обработчика события заблокировало бы Netty.
        exchange.onBackendWritable();
        ctx.fireChannelWritabilityChanged();
    }

    @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        // Команда остановит оба направления обмена и выберет допустимый исход ответа.
        exchange.cancel(cause);
    }

    @Override public void channelInactive(ChannelHandlerContext ctx) {
        // Закрытие соединения после полного ответа нормально: соединения не переиспользуются.
        // До последнего объекта финального ответа это ошибка, а не успешный EOF тела.
        if (!complete) exchange.cancel(new EOFException("Backend closed before final HTTP content"));
        ctx.fireChannelInactive();
    }

    /**
     * Запрещает принимать новые объекты и освобождает ещё не переданные потребителю.
     * Уже извлечённый объект по-прежнему принадлежит рабочему потоку команды.
     * Повторная очистка безопасна; поздний channelRead освободит свой объект сам.
     * Сообщения, удерживаемые предыдущим FlowControlHandler, освобождаются при закрытии канала.
     */
    void releaseQueued() {
        synchronized (received) {
            stopped = true;
            Object item;
            while ((item = received.poll()) != null) ReferenceCountUtil.release(item);
        }
    }
}
