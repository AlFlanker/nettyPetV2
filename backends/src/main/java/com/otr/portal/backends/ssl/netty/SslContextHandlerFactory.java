package com.otr.portal.backends.ssl.netty;

import com.otr.portal.ssl.support.netty.NettySslContextAdapter;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static com.otr.portal.ssl.support.netty.NettySslContextAdapter.HANDSHAKE_TIMEOUT_MILLIS;
import static java.util.Optional.ofNullable;

/**
 * Фабрика обработчиков SSL соединений
 */
@Component
@RequiredArgsConstructor
public class SslContextHandlerFactory {
    private final NettySslContextAdapter nettySslContextAdapter;
    private final Map<String, Optional<SslContext>> cache = new ConcurrentHashMap<>();

    public Optional<SslHandler> createSslHandler(SslHandlerContext context) {
        return ofNullable(context.getBackend().getSslContextKey())
                .filter(StringUtils::hasText)
                .flatMap(sslContextKey -> {
                    var sslContext = cache.computeIfAbsent(sslContextKey,
                            nettySslContextAdapter::forSslContextKey);
                    return sslContext.map(ctx -> fromContext(context.withSslContext(ctx)));
                });
    }

    private static SslHandler fromContext(SslHandlerContext context) {
        // SSLEngine c SNI (host,port)
        URI uri = URI.create(context.getBackend().fullBackendUrl());
        SSLEngine engine = context.getSslContext().newEngine(context.getChannel().alloc(), uri.getHost(), uri.getPort());
        SSLParameters sp = engine.getSSLParameters();
        // <a href="https://docs.oracle.com/en/java/javase/11/docs/specs/security/standard-names.html">Имена алгоритмов</a>
        sp.setEndpointIdentificationAlgorithm("HTTPS");
        engine.setSSLParameters(sp);

        SslHandler ssl = new SslHandler(engine);
        ssl.setHandshakeTimeoutMillis(Math.max(context.getBackend().timeout(), HANDSHAKE_TIMEOUT_MILLIS));

        return ssl;
    }
}
