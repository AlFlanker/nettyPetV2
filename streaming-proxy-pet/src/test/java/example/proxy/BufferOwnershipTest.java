package example.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** Проверяет счётчик ссылок ByteBuf напрямую: отсутствие сообщений LEAK ещё не доказывает освобождение памяти. */
class BufferOwnershipTest {
    @Test void responseConsumerReceivesOneOwnedReference() throws Exception {
        var handler = new BackendResponseHandler(mock(ProxyCommand.class));
        var channel = new EmbeddedChannel(handler);
        ByteBuf bytes = Unpooled.directBuffer(4).writeInt(42);
        var content = new DefaultHttpContent(bytes);
        try {
            channel.writeInbound(content);
            Object received = handler.next(100);
            assertThat(received).isSameAs(content);
            assertThat(bytes.refCnt()).isEqualTo(1);
            ReferenceCountUtil.release(received);
            assertThat(bytes.refCnt()).isZero();
        } finally {
            handler.releaseQueued();
            channel.finishAndReleaseAll();
        }
    }

    @Test void cancellationReleasesQueuedAndLateContentExactlyOnce() {
        var handler = new BackendResponseHandler(mock(ProxyCommand.class));
        var channel = new EmbeddedChannel(handler);
        ByteBuf queued = Unpooled.directBuffer(4).writeInt(1);
        ByteBuf late = Unpooled.directBuffer(4).writeInt(2);
        try {
            channel.writeInbound(new DefaultHttpContent(queued));
            handler.releaseQueued();
            assertThat(queued.refCnt()).isZero();
            channel.writeInbound(new DefaultHttpContent(late));
            assertThat(late.refCnt()).isZero();
            handler.releaseQueued(); // Повторное завершение не должно повторно освобождать тот же буфер.
        } finally {
            handler.releaseQueued();
            channel.finishAndReleaseAll();
        }
    }

    @Test void excessDemandFailsExchangeAndReleasesOnlyRejectedContent() {
        ProxyCommand exchange = mock(ProxyCommand.class);
        var handler = new BackendResponseHandler(exchange);
        var channel = new EmbeddedChannel(handler);
        ByteBuf accepted = Unpooled.directBuffer(4).writeInt(1);
        ByteBuf rejected = Unpooled.directBuffer(4).writeInt(2);
        try {
            channel.writeInbound(new DefaultHttpContent(accepted));
            channel.writeInbound(new DefaultHttpContent(rejected));
            assertThat(rejected.refCnt()).isZero();
            assertThat(accepted.refCnt()).isEqualTo(1);
            verify(exchange).cancel(isA(IllegalStateException.class));
            handler.releaseQueued();
            assertThat(accepted.refCnt()).isZero();
        } finally {
            handler.releaseQueued();
            channel.finishAndReleaseAll();
        }
    }

    @Test void channelCloseReleasesContentStillHeldByFlowControl() throws Exception {
        var handler = new BackendResponseHandler(mock(ProxyCommand.class));
        var channel = new EmbeddedChannel();
        channel.config().setAutoRead(false);
        // Как в Bootstrap: AUTO_READ выключен до установки и активации обработчиков.
        // Иначе EmbeddedChannel успевает запросить первое чтение автоматически.
        channel.pipeline().addLast(new FlowControlHandler(true), handler);
        ByteBuf first = Unpooled.directBuffer(4).writeInt(1);
        ByteBuf waiting = Unpooled.directBuffer(4).writeInt(2);
        try {
            channel.writeInbound(new DefaultHttpContent(first), new DefaultHttpContent(waiting));
            channel.read();
            Object received = handler.next(100);
            assertThat(received).isNotNull();
            ReferenceCountUtil.release(received);
            assertThat(first.refCnt()).isZero();
            assertThat(waiting.refCnt()).isEqualTo(1);
            channel.close();
            assertThat(waiting.refCnt()).isZero();
        } finally {
            handler.releaseQueued();
            channel.finishAndReleaseAll();
        }
    }
}
