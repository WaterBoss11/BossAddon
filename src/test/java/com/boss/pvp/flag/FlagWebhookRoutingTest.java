package com.boss.pvp.flag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Routing for which webhook a flag report is sent to ({@link FlagReporter#pickWebhook}). A combined (both-halves)
 * report prefers the dual channel, but must fall back to the single channel when the dual one isn't configured —
 * {@code pickWebhook(true, ...)} resolving to null would silently drop the report. A non-combined report always
 * uses the single channel (unchanged from before). This is the routing the fixed cross-addon detection now
 * reaches far more often, since BossUtility is always bundled post-merge.
 */
class FlagWebhookRoutingTest {

    private static final String SINGLE = "https://discord/single";
    private static final String DUAL = "https://discord/dual";

    @Test
    void combinedWithDualConfiguredUsesTheDualChannel() {
        assertEquals(DUAL, FlagReporter.pickWebhook(true, SINGLE, DUAL));
    }

    @Test
    void combinedWithoutDualFallsBackToTheSingleChannel() {
        assertEquals(SINGLE, FlagReporter.pickWebhook(true, SINGLE, null), "null dual -> single, never drop");
        assertEquals(SINGLE, FlagReporter.pickWebhook(true, SINGLE, ""), "blank dual -> single");
        assertEquals(SINGLE, FlagReporter.pickWebhook(true, SINGLE, "   "), "whitespace dual -> single");
    }

    @Test
    void nonCombinedAlwaysUsesTheSingleChannel() {
        assertEquals(SINGLE, FlagReporter.pickWebhook(false, SINGLE, DUAL), "not combined ignores the dual channel");
        assertEquals(SINGLE, FlagReporter.pickWebhook(false, SINGLE, null));
    }

    @Test
    void nothingConfiguredResolvesToNull() {
        // Reporting is gated on at least one channel being configured, but pickWebhook itself must not invent one.
        assertNull(FlagReporter.pickWebhook(true, null, null));
        assertNull(FlagReporter.pickWebhook(false, "", "   "));
    }
}
