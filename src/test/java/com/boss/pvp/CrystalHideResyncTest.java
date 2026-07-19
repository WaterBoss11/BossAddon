package com.boss.pvp;

import com.boss.pvp.util.pvp.CrystalHideManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the HCsCR-style resync bookkeeping in {@link CrystalHideManager}: a hidden crystal stays hidden for
 * its window, drops immediately once the server confirms the kill, and reappears (un-hides) if the window
 * elapses with no confirmation. The bounding-box mixin that actually blanks the hitbox is runtime-only and
 * covered by live testing, not here.
 */
class CrystalHideResyncTest {

    private static final IntPredicate NONE_REMOVED = id -> false;

    @BeforeEach
    void reset() {
        CrystalHideManager.clear();
    }

    @Test
    void hiddenUntilWindowElapsesThenReappears() {
        CrystalHideManager.hide(7, 3);
        assertTrue(CrystalHideManager.isHidden(7), "just hidden");

        CrystalHideManager.tick(NONE_REMOVED); // 3 -> 2
        assertTrue(CrystalHideManager.isHidden(7));
        CrystalHideManager.tick(NONE_REMOVED); // 2 -> 1
        assertTrue(CrystalHideManager.isHidden(7));
        CrystalHideManager.tick(NONE_REMOVED); // 1 -> 0, reappears
        assertFalse(CrystalHideManager.isHidden(7), "window elapsed without confirmation -> reappear");
    }

    @Test
    void defaultFourTickWindowHoldsThenReappears() {
        // The new default resync window (4 ticks): holds the spot clear for 4 ticks, then self-heals if the
        // server never confirmed the kill.
        CrystalHideManager.hide(9, 4);
        for (int i = 0; i < 3; i++) {
            CrystalHideManager.tick(NONE_REMOVED);
            assertTrue(CrystalHideManager.isHidden(9), "still hidden at tick " + (i + 1));
        }
        CrystalHideManager.tick(NONE_REMOVED);   // 4th tick: window elapses
        assertFalse(CrystalHideManager.isHidden(9), "reappears after the 4-tick window");
    }

    @Test
    void serverConfirmedKillDropsImmediately() {
        CrystalHideManager.hide(7, 20);
        CrystalHideManager.tick(id -> id == 7); // server says entity 7 is gone
        assertFalse(CrystalHideManager.isHidden(7), "confirmed kill -> stays gone");
        assertEquals(0, CrystalHideManager.hiddenCount());
    }

    @Test
    void confirmationMidWindowBeatsTheCountdown() {
        CrystalHideManager.hide(7, 20);
        CrystalHideManager.tick(NONE_REMOVED); // 20 -> 19, still hidden
        assertTrue(CrystalHideManager.isHidden(7));
        CrystalHideManager.tick(id -> id == 7); // confirmed before window would elapse
        assertFalse(CrystalHideManager.isHidden(7));
    }

    @Test
    void crystalsAreIndependent() {
        CrystalHideManager.hide(1, 1);
        CrystalHideManager.hide(2, 5);
        CrystalHideManager.tick(NONE_REMOVED); // id1: 1->0 reappears, id2: 5->4 stays
        assertFalse(CrystalHideManager.isHidden(1));
        assertTrue(CrystalHideManager.isHidden(2));
        assertEquals(1, CrystalHideManager.hiddenCount());
    }

    @Test
    void nonPositiveWindowStillHidesAtLeastOneTick() {
        CrystalHideManager.hide(7, 0);
        assertTrue(CrystalHideManager.isHidden(7), "0 clamps to a 1-tick minimum, not never-hidden");
        CrystalHideManager.tick(NONE_REMOVED);
        assertFalse(CrystalHideManager.isHidden(7));
    }

    @Test
    void tickOnEmptyIsANoOp() {
        CrystalHideManager.tick(NONE_REMOVED);
        assertEquals(0, CrystalHideManager.hiddenCount());
    }
}
