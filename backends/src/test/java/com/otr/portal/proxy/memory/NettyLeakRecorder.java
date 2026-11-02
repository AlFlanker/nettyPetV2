package com.otr.portal.proxy.memory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.util.ResourceLeakDetector;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Собирает отчёты {@link ResourceLeakDetector} о неосвобождённых буферах Netty.
 */
public final class NettyLeakRecorder implements AutoCloseable {
    private static final int TRACKING_ALLOCATIONS = 256;

    private final Logger logger = (Logger) LoggerFactory.getLogger(ResourceLeakDetector.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private NettyLeakRecorder() {
        appender.start();
        logger.addAppender(appender);
    }

    /**
     * Начинает перехватывать сообщения детектора; отчёты видны, только пока регистратор не закрыт.
     */
    public static NettyLeakRecorder start() {
        return new NettyLeakRecorder();
    }

    /**
     * Детектор сообщает об утечке, когда буфер собран GC, а следующий буфер взят на учёт.
     */
    public List<String> collectLeaks() throws InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            System.gc();
            Thread.sleep(100);
            for (int i = 0; i < TRACKING_ALLOCATIONS; i++) {
                ByteBuf probe = PooledByteBufAllocator.DEFAULT.directBuffer(16);
                probe.release();
            }
        }
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("LEAK"))
                .collect(Collectors.toList());
    }

    /**
     * Утечки буферов, созданных в {@code allocationSite}: в paranoid-режиме отчёт содержит стек создания буфера.
     */
    public List<String> collectLeaksCreatedIn(String allocationSite) throws InterruptedException {
        return collectLeaks().stream()
                .filter(message -> message.contains(allocationSite))
                .collect(Collectors.toList());
    }

    @Override
    public void close() {
        logger.detachAppender(appender);
        appender.stop();
    }
}
