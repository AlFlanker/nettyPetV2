package com.otr.portal.hosts.health;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author pobedenniy.alexey
 * @since 04.05.18
 */
public class BackendHealthHistoryTest {

    public static final int TIME_DELTA = 30;

    @Test
    public void counts_failed_ticks() {
        BackendHealthHistory history = new BackendHealthHistory(TIME_DELTA);
        history.tick(false);
        history.tick(true);
        history.tick(false);
        history.tick(true);

        List<BackendHealthHistory.BackendHealthView> result = history
                .toView()
                .collectList()
                .block();

        assertFalse(result.get(0).isFailed());
        assertTrue(result.get(1).isFailed());
        assertFalse(result.get(2).isFailed());
        assertTrue(result.get(3).isFailed());
    }

}
