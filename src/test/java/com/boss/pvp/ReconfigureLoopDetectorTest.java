package com.boss.pvp;

import com.boss.pvp.util.ReconfigureLoopDetector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure reconfigure-loop detection: it must trigger once N play&rarr;config transitions land inside the
 * window, and must NOT false-positive on a single legitimate reconfigure (resource-pack reload) or a few spread
 * out over time. Uses an injected clock (no real time, no Minecraft).
 */
class ReconfigureLoopDetectorTest {

    private static final int THRESHOLD = 3;
    private static final long WINDOW = 12_000L;

    @Test
    void triggersAfterThresholdWithinWindow() {
        ReconfigureLoopDetector d = new ReconfigureLoopDetector(THRESHOLD, WINDOW);
        assertFalse(d.record(0),      "1st reconfigure: no trigger");
        assertFalse(d.record(1_000),  "2nd reconfigure: no trigger");
        assertTrue(d.record(2_000),   "3rd within 2s: a loop -> trigger");
    }

    @Test
    void singleReconfigureNeverTriggers() {
        // The exact false-positive to avoid: one legitimate server-side resource-pack reload.
        ReconfigureLoopDetector d = new ReconfigureLoopDetector(THRESHOLD, WINDOW);
        assertFalse(d.record(5_000));
        assertEquals(1, d.windowCount());
    }

    @Test
    void sparseReconfiguresDoNotAccumulate() {
        // Legit reloads spaced further apart than the window never build up to the threshold.
        ReconfigureLoopDetector d = new ReconfigureLoopDetector(THRESHOLD, WINDOW);
        assertFalse(d.record(0));
        assertFalse(d.record(20_000));   // first event is now stale -> evicted
        assertFalse(d.record(40_000));   // still only one live event at a time
        assertEquals(1, d.windowCount());
    }

    @Test
    void oldEventsAreEvictedFromTheWindow() {
        ReconfigureLoopDetector d = new ReconfigureLoopDetector(THRESHOLD, WINDOW);
        d.record(0);
        d.record(1_000);
        assertEquals(2, d.windowCount());
        // At t=13_500 both 0 and 1_000 are older than the 12s window and drop out.
        assertFalse(d.record(13_500));
        assertEquals(1, d.windowCount());
    }

    @Test
    void twoInWindowIsNotYetALoop() {
        // Threshold is 3; two rapid reconfigures alone must not trip it.
        ReconfigureLoopDetector d = new ReconfigureLoopDetector(THRESHOLD, WINDOW);
        assertFalse(d.record(0));
        assertFalse(d.record(500));
        assertEquals(2, d.windowCount());
    }

    @Test
    void windowEdgeIsInclusive() {
        // Events at 0, 6000, 12000 — the oldest is exactly window-old (12000-0 == window, not > window), so all
        // three are still counted and the third trips it.
        ReconfigureLoopDetector d = new ReconfigureLoopDetector(THRESHOLD, WINDOW);
        assertFalse(d.record(0));
        assertFalse(d.record(6_000));
        assertTrue(d.record(12_000));
    }

    @Test
    void resetClearsTheWindow() {
        ReconfigureLoopDetector d = new ReconfigureLoopDetector(THRESHOLD, WINDOW);
        d.record(0);
        d.record(100);
        d.reset();
        assertEquals(0, d.windowCount());
        assertFalse(d.record(200), "after reset the burst is forgotten");
    }
}
