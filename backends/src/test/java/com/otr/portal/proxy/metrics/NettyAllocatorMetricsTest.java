package com.otr.portal.proxy.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NettyAllocatorMetricsTest {

    @Test
    void reportsPooledAllocatorMemory() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new NettyAllocatorMetrics(registry);
        ByteBuf buffer = PooledByteBufAllocator.DEFAULT.directBuffer(1024);
        try {
            assertThat(registry.get(NettyAllocatorMetrics.USED_DIRECT_MEMORY_METRIC_NAME).gauge().value()).isPositive();
            assertThat(registry.get(NettyAllocatorMetrics.USED_HEAP_MEMORY_METRIC_NAME).gauge().value()).isNotNegative();
        } finally {
            buffer.release();
        }
    }
}
