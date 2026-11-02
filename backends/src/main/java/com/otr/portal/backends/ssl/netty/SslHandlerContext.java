package com.otr.portal.backends.ssl.netty;

import com.otr.portal.proxy.BackendInfo;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslContext;
import lombok.Value;
import lombok.With;

/**
 * Вспомогательный класс для формирования  SslHandler
 *
 * @author alexraspopin
 */
@Value(staticConstructor = "of")
public class SslHandlerContext {
    @With
    SslContext sslContext;
    SocketChannel channel;
    BackendInfo backend;

    public static SslHandlerContext of(
            SocketChannel channel,
            BackendInfo backend
    ) {
        return of(null, channel, backend);
    }
}
