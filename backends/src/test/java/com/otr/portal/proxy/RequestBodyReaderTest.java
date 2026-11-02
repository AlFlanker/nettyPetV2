package com.otr.portal.proxy;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.otr.portal.proxy.RequestBodyReader.CHUNK_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Чтение тела запроса в {@link RequestBodyReader}. Тело должно лечь в pooled direct-память кусками по 64 КиБ:
 * без массивов в куче, растущих вместе с телом, и без склейки кусков в один буфер, которая копирует всё тело.
 * Модульный тест: только поток и {@code CompositeByteBuf}, без сервлет-контейнера и Netty-канала.
 */
class RequestBodyReaderTest {
    private final CompositeByteBuf body = PooledByteBufAllocator.DEFAULT.compositeDirectBuffer(Integer.MAX_VALUE);

    /**
     * Буфер взят из пула: без освобождения его память не вернётся в пул, а детектор утечек сообщит о ней.
     */
    @AfterEach
    void releaseBody() {
        body.release();
    }

    /**
     * Тело чуть больше двух кусков делится на 64 КиБ + 64 КиБ + остаток, куски direct, байты не меняются.
     * Ловит возврат к одному растущему буферу (кусок будет один), heap-буфер вместо direct
     * и потерю или перестановку байтов на границе кусков.
     */
    @Test
    void splitsBodyIntoDirectChunks() throws IOException {
        byte[] data = randomBytes(2 * CHUNK_SIZE + 100);

        RequestBodyReader.readBody(new ByteArrayInputStream(data), body);

        assertThat(componentSizes()).containsExactly(CHUNK_SIZE, CHUNK_SIZE, 100);
        assertThat(body.isDirect()).isTrue();
        assertThat(ByteBufUtil.getBytes(body)).isEqualTo(data);
    }

    /**
     * Поток отдаёт данные порциями по 1000 байт, как {@code ServletInputStream} при медленной сети,
     * а куски всё равно заполняются до 64 КиБ. Иначе каждая мелкая порция стала бы отдельным компонентом:
     * тело 100 МБ дало бы около ста тысяч компонентов и столько же служебных объектов в куче.
     */
    @Test
    void fillsChunksWhenStreamReturnsSmallPortions() throws IOException {
        byte[] data = randomBytes(2 * CHUNK_SIZE + 100);

        RequestBodyReader.readBody(new TrickleInputStream(data, 1000), body);

        assertThat(componentSizes()).containsExactly(CHUNK_SIZE, CHUNK_SIZE, 100);
        assertThat(ByteBufUtil.getBytes(body)).isEqualTo(data);
    }

    /**
     * Пустой поток не добавляет компонентов: пустой кусок не занимает память пула,
     * а {@code buildFullHttpRequest} по нулевой длине не выставит {@code Content-Length}.
     */
    @Test
    void leavesBodyEmptyForEmptyStream() throws IOException {
        RequestBodyReader.readBody(new ByteArrayInputStream(new byte[0]), body);

        assertThat(body.numComponents()).isZero();
        assertThat(body.readableBytes()).isZero();
    }

    /**
     * При обрыве загрузки исключение пробрасывается, а прочитанные до обрыва куски остаются в {@code body}.
     * Читатель их не освобождает: владелец буфера — {@code buildFullHttpRequest}, он освобождает весь запрос
     * в {@code catch}. Так нет ни утечки кусков, ни двойного освобождения.
     */
    @Test
    void keepsChunksReadBeforeFailure() {
        InputStream aborted = new AbortedInputStream(randomBytes(CHUNK_SIZE + 100));

        assertThatThrownBy(() -> RequestBodyReader.readBody(aborted, body))
                .isInstanceOf(IOException.class);
        assertThat(componentSizes()).containsExactly(CHUNK_SIZE);
    }

    /**
     * Размеры компонентов по порядку: по ним видно, как тело поделено на куски.
     */
    private List<Integer> componentSizes() {
        List<Integer> sizes = new ArrayList<>();
        for (int i = 0; i < body.numComponents(); i++) {
            sizes.add(body.component(i).readableBytes());
        }
        return sizes;
    }

    /**
     * Случайные, но воспроизводимые данные: перестановка байтов не спрячется за повторяющимся шаблоном.
     */
    private static byte[] randomBytes(int size) {
        byte[] data = new byte[size];
        new Random(size).nextBytes(data);
        return data;
    }

    /**
     * Отдаёт данные порциями не больше {@code portion} байт — как {@code ServletInputStream} при медленной сети.
     */
    private static final class TrickleInputStream extends ByteArrayInputStream {
        private final int portion;

        private TrickleInputStream(byte[] data, int portion) {
            super(data);
            this.portion = portion;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            return super.read(b, off, Math.min(len, portion));
        }
    }
}
