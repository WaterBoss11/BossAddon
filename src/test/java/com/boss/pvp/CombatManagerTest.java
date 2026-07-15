package com.boss.pvp;

import com.boss.pvp.util.CombatManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CombatManager} — the shared combat-pause arbiter. Pure static logic, no Minecraft or
 * AUTISM dependency, so it is fully unit-testable and CI-safe.
 */
class CombatManagerTest {

    @BeforeEach
    void reset() {
        CombatManager.reset();
    }

    @Test
    void testNotPausedByDefault() {
        assertFalse(CombatManager.isCombatPaused(), "fresh state should not be paused");
    }

    @Test
    void testPauseBlocksForNTicks() {
        CombatManager.pauseCombat(3);
        assertTrue(CombatManager.isCombatPaused(), "paused immediately after pauseCombat(3)");
        CombatManager.tick();
        assertTrue(CombatManager.isCombatPaused(), "still paused after 1 tick (2 left)");
        CombatManager.tick();
        assertTrue(CombatManager.isCombatPaused(), "still paused after 2 ticks (1 left)");
        CombatManager.tick();
        assertFalse(CombatManager.isCombatPaused(), "unpaused after the 3rd tick");
    }

    @Test
    void testPauseZeroImmediate() {
        CombatManager.pauseCombat(0);
        assertFalse(CombatManager.isCombatPaused(), "pauseCombat(0) should not pause");
    }

    @Test
    void testPauseExtendsTakesMax() {
        CombatManager.pauseCombat(3);
        CombatManager.pauseCombat(5);   // takes the max (5), not additive (8)
        for (int i = 0; i < 4; i++) {
            CombatManager.tick();
            assertTrue(CombatManager.isCombatPaused(), "still paused during the 5-tick window (tick " + (i + 1) + ")");
        }
        CombatManager.tick();   // 5th tick
        assertFalse(CombatManager.isCombatPaused(), "unpaused after 5 ticks — proves max(3,5)=5, not additive 8");
    }

    @Test
    void testTickDecrements() {
        CombatManager.pauseCombat(2);
        CombatManager.tick();
        assertTrue(CombatManager.isCombatPaused(), "1 tick left");
        CombatManager.tick();
        assertFalse(CombatManager.isCombatPaused(), "0 left");
        CombatManager.tick();   // must not underflow below 0
        assertFalse(CombatManager.isCombatPaused(), "still unpaused; tick() at 0 does not underflow");
    }

    @Test
    void testPauseDuringPause() {
        // A shorter pause requested while already paused must NOT shrink the remaining window (takes the max).
        CombatManager.pauseCombat(3);
        CombatManager.pauseCombat(1);   // 1 < 3, so no effect
        CombatManager.tick();
        CombatManager.tick();
        assertTrue(CombatManager.isCombatPaused(), "still paused after 2 ticks — the 3-tick window was kept");
    }

    @Test
    void testMultipleTicksDecrement() {
        CombatManager.pauseCombat(4);
        assertTrue(CombatManager.isCombatPaused());
        CombatManager.tick();
        assertTrue(CombatManager.isCombatPaused(), "3 left");
        CombatManager.tick();
        assertTrue(CombatManager.isCombatPaused(), "2 left");
        CombatManager.tick();
        assertTrue(CombatManager.isCombatPaused(), "1 left");
        CombatManager.tick();
        assertFalse(CombatManager.isCombatPaused(), "0 left after exactly 4 ticks");
    }
}
