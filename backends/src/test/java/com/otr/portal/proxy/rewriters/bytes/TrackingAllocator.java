package com.otr.portal.proxy.rewriters.bytes;

import io.netty.buffer.AbstractByteBufAllocator;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.UnpooledByteBufAllocator;

import java.util.ArrayList;
import java.util.List;

/**
 * Аллокатор для тестов: запоминает все выданные буферы, чтобы проверить их освобождение.
 */
final class TrackingAllocator extends AbstractByteBufAllocator {
    private final List<ByteBuf> allocated = new ArrayList<>();

    TrackingAllocator() {
        super(true);
    }

    @Override
    protected ByteBuf newHeapBuffer(int initialCapacity, int maxCapacity) {
        return track(UnpooledByteBufAllocator.DEFAULT.heapBuffer(initialCapacity, maxCapacity));
    }

    @Override
    protected ByteBuf newDirectBuffer(int initialCapacity, int maxCapacity) {
        return track(UnpooledByteBufAllocator.DEFAULT.directBuffer(initialCapacity, maxCapacity));
    }

    @Override
    public CompositeByteBuf compositeDirectBuffer(int maxNumComponents) {
        return track(super.compositeDirectBuffer(maxNumComponents));
    }

    @Override
    public boolean isDirectBufferPooled() {
        return false;
    }

    /**
     * @return буферы, которые всё ещё удерживаются
     */
    List<ByteBuf> findUnreleased() {
        List<ByteBuf> unreleased = new ArrayList<>();
        for (ByteBuf buf : allocated) {
            if (buf.refCnt() > 0) {
                unreleased.add(buf);
            }
        }
        return unreleased;
    }

    /**
     * Тело, разрезанное на мелкие direct-компоненты: поиск и срезы должны работать через их границы.
     */
    CompositeByteBuf splitIntoComponents(byte[] bytes, int maxComponentSize) {
        CompositeByteBuf body = compositeDirectBuffer(Integer.MAX_VALUE);
        int offset = 0;
        int step = 1;
        while (offset < bytes.length) {
            int length = Math.min(step, bytes.length - offset);
            ByteBuf part = directBuffer(length);
            part.writeBytes(bytes, offset, length);
            body.addComponent(true, part);
            offset += length;
            step = step % maxComponentSize + 1;
        }
        return body;
    }

    private <T extends ByteBuf> T track(T buf) {
        allocated.add(buf);
        return buf;
    }
}
