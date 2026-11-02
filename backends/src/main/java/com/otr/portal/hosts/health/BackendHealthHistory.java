package com.otr.portal.hosts.health;

import reactor.core.publisher.Flux;
import rx.Observable;

/**
 * @author pobedenniy.alexey
 * @since 04.05.18
 */
public class BackendHealthHistory {
    public static final int RESOLUTION = 64;
    private long timeStamp;
    private long bitSet = 0L;
    private int overflow = 0;
    private final long timeDelta;

    public BackendHealthHistory(long timeDelta) {
        this.timeDelta = timeDelta;
        timeStamp = System.currentTimeMillis();
    }


    public void tick(boolean failed) {
        if (overflow >= RESOLUTION) {
            timeStamp += timeDelta;
        } else {
            overflow++;
        }
        bitSet = bitSet << 1;
        bitSet += failed ? 1 : 0;
    }

    public Flux<BackendHealthView> toView() {
        return Flux.create(f -> {
                    long currentTimeStamp = timeStamp;
                    for (int i = Math.min(RESOLUTION, overflow) - 1; i >= 0 && !f.isCancelled(); i--) {
                        boolean failed = (bitSet >> i & 0x1) == 1;
                        f.next(new BackendHealthView(currentTimeStamp, failed));
                        currentTimeStamp += timeDelta;
                    }
                    f.complete();
                }
        );
    }

    public static class BackendHealthView {
        private final long timeStamp;
        private final boolean failed;

        public BackendHealthView(long timeStamp, boolean failed) {

            this.timeStamp = timeStamp;
            this.failed = failed;
        }

        public long getTimeStamp() {
            return timeStamp;
        }

        public boolean isFailed() {
            return failed;
        }
    }
}
