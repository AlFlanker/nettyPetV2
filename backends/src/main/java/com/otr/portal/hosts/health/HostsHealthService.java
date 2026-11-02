package com.otr.portal.hosts.health;

import com.google.common.collect.Lists;
import com.otr.portal.core.health.ServerProblemApplicationEvent;
import com.otr.portal.hosts.Backend;
import com.otr.portal.hosts.HostsService;
import com.otr.portal.hosts.backends.metadata.BackendTypeMetadataExtension;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static java.util.Optional.ofNullable;

/**
 * @author pobedenniy.alexey
 * @since 28.11.17
 */
@Service
public class HostsHealthService {
    public static final int TIME_DELTA = 60_000;
    private static final int NOTIFICATION_THRESHOLD_MS = 1000 * 60 * 5;
    private final HostsService hostsService;
    private final Bootstrap bootstrap;
    private final ApplicationEventPublisher applicationEventPublisher;
    private List<BackendHealth> health;
    private List<SystemHeaderInterceptor> interceptors;
    private Map<String, BackendHealthNotifyStatus> failedBackends = new ConcurrentHashMap<>();
    private Map<String, BackendHealthHistory> backendHealthHistory = new ConcurrentHashMap<>();


    public HostsHealthService(HostsService hostsService, ApplicationEventPublisher applicationEventPublisher, @Qualifier("generalNettyPool") Bootstrap bootstrap, @Autowired(required = false) List<SystemHeaderInterceptor> interceptors) {
        this.hostsService = hostsService;
        this.applicationEventPublisher = applicationEventPublisher;
        this.bootstrap = bootstrap;
        this.interceptors = ofNullable(interceptors).orElseGet(Collections::emptyList);
    }


    @Scheduled(initialDelay = 20_000, fixedDelay = TIME_DELTA)
    public void updateHistory() {
        hostsService
                .getBackends()
                .forEach(backend -> {
                    backendHealthHistory
                            .computeIfAbsent(backend.name(), k -> new BackendHealthHistory(TIME_DELTA))
                            .tick(failedBackends.containsKey(backend.name()));
                });
    }

    @Scheduled(initialDelay = 15_000, fixedDelay = 5000)
    public void checkHealth() {
        this.health = Lists.newArrayList(
                Flux
                        .fromIterable(hostsService.getBackends())
                        .filter(Backend::hasHealthUri)
                        .flatMap(backend ->
                                this.checkBackend(backend)
                                        .map(this::notifyOnFailure)
                                        .timeout(Duration.ofMillis(5000),
                                                Flux.defer(() ->
                                                        Flux.just(new BackendHealth(backend.getName(), backend.getExtFieldValue(BackendTypeMetadataExtension.BACKEND_TYPE_FIELD_NAME), HttpStatus.REQUEST_TIMEOUT.value()))))
                        )
                        .toIterable());
    }

    private BackendHealth notifyOnFailure(BackendHealth backendHealth) {
        final String key = backendHealth.backendName();

        if (backendHealth.isOK()) {
            failedBackends.remove(key);
            return backendHealth;
        }
        if (failedBackends.containsKey(key)) {
            final BackendHealthNotifyStatus notifyStatus = failedBackends.get(key);
            if (notifyStatus.thresholdMeet()) {
                notifyStatus.notified();
                applicationEventPublisher.publishEvent(new ServerProblemApplicationEvent(String.format("Бэкенд %s недоступен более чем %s", backendHealth.backendName(), notifyStatus.humanTime())));
            }
        } else {
            failedBackends.put(key, new BackendHealthNotifyStatus(NOTIFICATION_THRESHOLD_MS));
        }
        return backendHealth;
    }

    @NotNull
    private Flux<BackendHealth> checkBackend(Backend backend) {
        URI uri = URI.create(backend.fullBackendUrl(backend.appendPrefixTo(backend.getHealthUri())));
        return checkBackend(backend.getName(), backend.getExtFieldValue(BackendTypeMetadataExtension.BACKEND_TYPE_FIELD_NAME), uri);
    }

    public Flux<BackendHealth> checkBackend(String name, String backendType, URI uri) {
        String authority = uri.getRawAuthority();
        String path = uri.toString().substring(uri.toString().indexOf(authority) + authority.length());
        return Flux.create(f -> {
            if (!f.isCancelled()) {
                bootstrap.connect(uri.getHost(), getPort(uri)).addListener((ChannelFutureListener) future -> {
                    if (future.isSuccess()) {
                        final Channel channel = future.channel();
                        channel.pipeline().addLast(new HttpObjectAggregator(1024 * 10));
                        channel.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpResponse>() {
                            @Override
                            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                                f.next(new BackendHealth(name, backendType, 503, cause));
                                f.complete();
                                channel.close();
                            }

                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) throws Exception {
                                f.next(new BackendHealth(name, backendType, msg.status().code(), msg.headers()));
                                f.complete();
                                channel.close();
                            }
                        });
                        final FullHttpRequest nettyRequest = new DefaultFullHttpRequest(
                                HttpVersion.HTTP_1_1, HttpMethod.HEAD, path);
                        nettyRequest.headers().set(org.springframework.http.HttpHeaders.HOST, uri.getHost() + ":" + getPort(uri));
                        nettyRequest.headers().set(org.springframework.http.HttpHeaders.CONNECTION, "close");
                        interceptors.forEach(i -> i.systemHeadersForBackend(name).forEach((key, value) -> nettyRequest.headers().set(key, value)));

                        channel.writeAndFlush(nettyRequest);
                    } else {
                        f.next(new BackendHealth(name, backendType, HttpStatus.REQUEST_TIMEOUT.value()));
                        f.complete();
                    }
                });

            }
        });
    }

    public List<BackendHealth> getHealth() {
        return health;
    }

    private int getPort(URI uri) {
        int port = uri.getPort();
        if (port == -1) {
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                port = 80;
            } else if ("https".equalsIgnoreCase(uri.getScheme())) {
                port = 443;
            }
        }
        return port;
    }

    public Optional<BackendHealth> getHealth(String backend) {
        return ofNullable(health).map(Collection::stream).orElse(Stream.empty()).filter(b -> b.backendName().equals(backend)).findFirst();
    }

    public Map<String, List<BackendHealthHistory.BackendHealthView>> getHealthHistory() {
        Map<String, List<BackendHealthHistory.BackendHealthView>> result = new HashMap<>();
        backendHealthHistory.forEach((k, v) -> result.put(k, v.toView().collectList().block()));
        return result;
    }

    private static class BackendHealthNotifyStatus {
        private final long createdAt;
        private Status status;
        private int notificationThresholdMs;

        BackendHealthNotifyStatus(int notificationThresholdMs) {
            this.notificationThresholdMs = notificationThresholdMs;
            this.status = Status.INITIAL;
            this.createdAt = System.currentTimeMillis();
        }

        boolean thresholdMeet() {
            return status == Status.INITIAL && createdAt + notificationThresholdMs <= System.currentTimeMillis();
        }

        void notified() {
            this.status = Status.NOTIFIED;
        }

        String humanTime() {
            int hours = notificationThresholdMs / (1000 * 60 * 60);
            int minutes = notificationThresholdMs / (1000 * 60);
            if (hours > 0) {
                switch (hours) {
                    case 1:
                        return "1 час";
                    //fallthrough
                    case 2:
                    case 3:
                    case 4:
                        return String.format("%d часа", hours);
                    default:
                        return String.format("%d часов", hours);
                }
            } else {
                switch (minutes) {
                    case 1:
                        return "1 минуту";
                    //fallthrough
                    case 2:
                    case 3:
                    case 4:
                        return String.format("%d минуты", minutes);
                    default:
                        return String.format("%d минут", minutes);
                }
            }
        }

        private enum Status {
            INITIAL, NOTIFIED
        }
    }
}
