package com.otr.portal.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.Epoll;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Выбор транспорта Netty для бэкендов. Группа event loop и класс канала должны быть одного транспорта:
 * иначе {@link Bootstrap} откажет в регистрации канала, и прокси не подключится ни к одному бэкенду.
 * Epoll проверяется только там, где нативная библиотека загрузилась (Linux x86_64), на остальных ОС тест пропускается.
 */
class NettyTransportTest {

    /**
     * Запасной транспорт доступен везде: NIO-группа и NIO-канал устанавливают соединение.
     */
    @Test
    void nioTransportConnectsToServer() throws Exception {
        assertConnects(NettyTransport.NIO);
    }

    /**
     * На Linux основной транспорт — epoll: его группа и канал устанавливают соединение.
     */
    @Test
    void epollTransportConnectsToServer() throws Exception {
        assumeTrue(Epoll.isAvailable(), () -> "epoll недоступен: " + Epoll.unavailabilityCause());
        assertConnects(NettyTransport.EPOLL);
    }

    /**
     * Epoll выбирается, если нативная библиотека загрузилась, иначе — NIO, а не ошибка при создании первого бэкенда.
     */
    @Test
    void detectsEpollWhenAvailableAndNioOtherwise() {
        NettyTransport expected = Epoll.isAvailable() ? NettyTransport.EPOLL : NettyTransport.NIO;

        assertThat(NettyTransport.detect()).isEqualTo(expected);
    }

    /**
     * Подключается к локальному порту через группу и канал транспорта. Сервер не принимает соединение явно:
     * TCP-рукопожатие завершает ядро, этого достаточно, чтобы канал стал активным.
     */
    private static void assertConnects(NettyTransport transport) throws Exception {
        EventLoopGroup group = transport.createEventLoopGroup(1, new DefaultThreadFactory("transport-test"));
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Channel channel = new Bootstrap()
                    .group(group)
                    .channel(transport.socketChannelClass())
                    .handler(new ChannelInboundHandlerAdapter())
                    .connect(server.getInetAddress(), server.getLocalPort())
                    .sync()
                    .channel();
            assertThat(channel.isActive()).isTrue();
            channel.close().sync();
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }
}
