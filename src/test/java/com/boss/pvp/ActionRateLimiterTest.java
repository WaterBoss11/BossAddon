package com.boss.pvp;

import com.boss.pvp.util.pvp.ActionRateLimiter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the shared per-second cap for AutoCrystal breaks ({@link ActionRateLimiter}) — the honest replacement
 * for the old fixed "1 per 2 ticks" throttle, cooperating across the tick loop and the fast-break path.
 */
class ActionRateLimiterTest {

    @Test
    void allowsUpToCapWithinASecondThenDenies() {
        ActionRateLimiter r = new ActionRateLimiter();
        assertTrue(r.tryAcquire(0L, 3));
        assertTrue(r.tryAcquire(10L, 3));
        assertTrue(r.tryAcquire(20L, 3));
        assertFalse(r.tryAcquire(30L, 3), "4th within the same second is denied");
        assertEquals(3, r.countWithinLastSecond(30L));
    }

    @Test
    void slotsFreeAsTheWindowSlidesForward() {
        ActionRateLimiter r = new ActionRateLimiter();
        r.tryAcquire(0L, 2);
        r.tryAcquire(100L, 2);
        assertFalse(r.tryAcquire(200L, 2), "cap reached");
        // The first stamp (t=0) ages out at t>=1000, freeing a slot.
        assertTrue(r.tryAcquire(1_000L, 2));
        assertEquals(2, r.countWithinLastSecond(1_000L));   // t=100 and t=1000
    }

    @Test
    void capIsRespectedAsSettingChanges() {
        ActionRateLimiter r = new ActionRateLimiter();
        assertTrue(r.tryAcquire(0L, 1));
        assertFalse(r.tryAcquire(10L, 1), "cap 1 reached");
        // A higher cap (user raised maxBps) immediately allows more within the same window.
        assertTrue(r.tryAcquire(20L, 5));
    }

    @Test
    void nonPositiveCapAlwaysDenies() {
        ActionRateLimiter r = new ActionRateLimiter();
        assertFalse(r.tryAcquire(0L, 0));
        assertFalse(r.tryAcquire(0L, -3));
    }

    @Test
    void clearResetsTheWindow() {
        ActionRateLimiter r = new ActionRateLimiter();
        r.tryAcquire(0L, 1);
        r.clear();
        assertTrue(r.tryAcquire(10L, 1), "after clear the window is empty");
    }
}
