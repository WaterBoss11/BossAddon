package com.boss.pvp.flag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the dedupe window that stops a kick→reconnect→kick loop from spamming the flags channel
 * ({@link FlagReporter#isDuplicate}).
 */
class FlagDedupeTest {

    private static final long WINDOW = 30_000L;

    @Test
    void sameReasonInsideWindowIsDuplicate() {
        assertTrue(FlagReporter.isDuplicate("KICK|afk", 1_000L, "KICK|afk", 1_000L + 5_000L, WINDOW));
    }

    @Test
    void sameReasonAfterWindowIsNotDuplicate() {
        assertFalse(FlagReporter.isDuplicate("KICK|afk", 1_000L, "KICK|afk", 1_000L + 31_000L, WINDOW));
    }

    @Test
    void differentReasonIsNotDuplicate() {
        assertFalse(FlagReporter.isDuplicate("KICK|afk", 1_000L, "KICK|banned", 1_500L, WINDOW));
    }

    @Test
    void differentTypeSameReasonIsNotDuplicate() {
        assertFalse(FlagReporter.isDuplicate("KICK|lag", 1_000L, "PACKET_KICK|lag", 1_500L, WINDOW));
    }

    @Test
    void noPriorSendIsNotDuplicate() {
        assertFalse(FlagReporter.isDuplicate(null, 0L, "CRASH|npe", 1_000L, WINDOW));
    }
}
