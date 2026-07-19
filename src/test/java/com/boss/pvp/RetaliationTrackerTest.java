package com.boss.pvp;

import com.boss.pvp.util.pvp.RetaliationTracker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the retaliation memory behind KillAura's "Retaliation" activation mode
 * ({@link RetaliationTracker}): a mob becomes targetable only after a recorded hit, stays targetable for
 * the memory window, expires after it, and the mode never restricts players or Always mode.
 */
class RetaliationTrackerTest {

    private static final long WINDOW = 10_000L;   // default 10s memory

    @Test
    void mobActiveAfterSyntheticHurt() {
        RetaliationTracker t = new RetaliationTracker();
        t.record(42, 1_000L);
        assertTrue(t.isActive(42, 1_000L, WINDOW));            // immediately
        assertTrue(t.isActive(42, 1_000L + WINDOW, WINDOW));   // inclusive at the boundary
    }

    @Test
    void expiresAfterWindow() {
        RetaliationTracker t = new RetaliationTracker();
        t.record(42, 1_000L);
        assertFalse(t.isActive(42, 1_000L + WINDOW + 1, WINDOW));
    }

    @Test
    void unknownMobNeverActive() {
        RetaliationTracker t = new RetaliationTracker();
        assertFalse(t.isActive(7, 1_000L, WINDOW));
    }

    @Test
    void newHitRefreshesWindowSoTargetDoesNotDropMidFight() {
        RetaliationTracker t = new RetaliationTracker();
        t.record(42, 1_000L);
        t.record(42, 9_000L);   // hits again mid-fight
        assertTrue(t.isActive(42, 9_000L + WINDOW, WINDOW));    // window counts from the LATEST hit
        assertFalse(t.isActive(42, 9_000L + WINDOW + 1, WINDOW));
    }

    @Test
    void independentPerMob() {
        RetaliationTracker t = new RetaliationTracker();
        t.record(1, 1_000L);
        assertTrue(t.isActive(1, 2_000L, WINDOW));
        assertFalse(t.isActive(2, 2_000L, WINDOW));   // a different mob is not implicated
    }

    @Test
    void pruneReclaimsExpiredEntries() {
        RetaliationTracker t = new RetaliationTracker();
        t.record(1, 1_000L);
        t.record(2, 50_000L);
        t.prune(50_000L, WINDOW);
        assertEquals(1, t.size());                     // id 1 expired and was reclaimed
        assertTrue(t.isActive(2, 50_000L, WINDOW));    // id 2 survives
    }

    @Test
    void clearEmptiesMemory() {
        RetaliationTracker t = new RetaliationTracker();
        t.record(1, 1_000L);
        t.clear();
        assertEquals(0, t.size());
        assertFalse(t.isActive(1, 1_000L, WINDOW));
    }

    // --- shouldTarget: the activation decision (mode/player guarantees) ---

    @Test
    void alwaysModeTargetsEverythingRegardlessOfMemory() {
        // Always mode: no regression — memory state is irrelevant for mobs and players alike.
        assertTrue(RetaliationTracker.shouldTarget(false, false, false));
        assertTrue(RetaliationTracker.shouldTarget(false, false, true));
        assertTrue(RetaliationTracker.shouldTarget(false, true, false));
    }

    @Test
    void retaliationModeNeverAffectsPlayers() {
        // Players stay targetable exactly as today, active memory or not.
        assertTrue(RetaliationTracker.shouldTarget(true, true, false));
        assertTrue(RetaliationTracker.shouldTarget(true, true, true));
    }

    @Test
    void retaliationModeGatesMobsOnActiveMemory() {
        assertFalse(RetaliationTracker.shouldTarget(true, false, false));   // mob that never hit us: skipped
        assertTrue(RetaliationTracker.shouldTarget(true, false, true));     // mob that retaliated: targetable
    }
}
