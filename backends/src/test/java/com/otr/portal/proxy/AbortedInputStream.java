package com.otr.portal.proxy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Отдаёт полученную часть тела, затем бросает {@link IOException} — как обрыв загрузки клиентом.
 */
final class AbortedInputStream extends InputStream {
    private final ByteArrayInputStream received;

    AbortedInputStream(byte[] received) {
        this.received = new ByteArrayInputStream(received);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        return requireData(received.read(b, off, len));
    }

    @Override
    public int read() throws IOException {
        return requireData(received.read());
    }

    private static int requireData(int read) throws IOException {
        if (read < 0) {
            throw new IOException("Клиент оборвал загрузку");
        }
        return read;
    }
}
