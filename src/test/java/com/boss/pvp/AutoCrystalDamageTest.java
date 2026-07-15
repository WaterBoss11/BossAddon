package com.boss.pvp;

import com.boss.pvp.util.pvp.DamageUtil;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the crystal explosion-damage math extracted into {@link DamageUtil}. Covers the pure,
 * world-free pieces: the raw damage formula, EPF accumulation, and the anti-suicide gate.
 *
 * Not covered: {@code effectiveDamage} / {@code crystalDamage}, which need a live armored entity + world
 * raycast (CombatRules, getSeenPercent) — those require Fabric game tests, not plain unit tests.
 */
class AutoCrystalDamageTest {

    private static final double EPS = 1.0e-6;

    // --- rawExplosionDamage: ((e^2+e)/2) * 7 * range + 1, range = 2*power, e = exposure*(1 - dist/range) ---

    @Test
    void testRawDamageAtZeroDistance() {
        // exposure=1, distance=0, power=6 -> decay=1, e=1 -> (1)*7*12 + 1 = 85 (the max for a crystal).
        assertEquals(85.0, DamageUtil.rawExplosionDamage(1.0, 0.0, DamageUtil.CRYSTAL_POWER), EPS);
    }

    @Test
    void testRawDamageAtMaxRange() {
        assertEquals(0.0, DamageUtil.rawExplosionDamage(1.0, 12.0, DamageUtil.CRYSTAL_POWER), EPS,
            "at exactly the 12-block range, damage is 0");
        assertEquals(0.0, DamageUtil.rawExplosionDamage(1.0, 13.0, DamageUtil.CRYSTAL_POWER), EPS,
            "beyond range, damage is 0");
    }

    @Test
    void testRawDamageMidpoint() {
        // distance=6 -> decay=0.5, e=0.5 -> ((0.25+0.5)/2)*84 + 1 = 0.375*84 + 1 = 32.5.
        assertEquals(32.5, DamageUtil.rawExplosionDamage(1.0, 6.0, DamageUtil.CRYSTAL_POWER), EPS);
    }

    @Test
    void testLowerExposureLowersDamage() {
        double full = DamageUtil.rawExplosionDamage(1.0, 3.0, DamageUtil.CRYSTAL_POWER);
        double half = DamageUtil.rawExplosionDamage(0.5, 3.0, DamageUtil.CRYSTAL_POWER);
        assertTrue(half < full, "half exposure must yield less damage than full exposure at the same distance");
    }

    // --- epfFromLevels: Protection 1/level, Blast Protection 2/level, capped at 20 ---

    @Test
    void testEpfBlastCountsDoubleVsProtection() {
        assertEquals(2, DamageUtil.epfFromLevels(0, 1), "1 level of Blast Protection = 2 EPF");
        assertEquals(1, DamageUtil.epfFromLevels(1, 0), "1 level of Protection = 1 EPF");
        assertTrue(DamageUtil.epfFromLevels(0, 1) > DamageUtil.epfFromLevels(1, 0),
            "Blast Protection counts double vs Protection per level");
    }

    @Test
    void testEpfCap() {
        assertEquals(20, DamageUtil.epfFromLevels(30, 30), "EPF is capped at 20");
        assertEquals(20, DamageUtil.epfFromLevels(100, 0), "EPF never exceeds 20 regardless of levels");
    }

    // --- wouldSelfKill: anti-suicide gate (selfDamage >= health + absorption) ---

    @Test
    void testAntiSuicideCondition() {
        assertTrue(DamageUtil.wouldSelfKill(20.0, 10.0, 4.0), "20 self-damage vs 14 effective health is lethal");
        assertTrue(DamageUtil.wouldSelfKill(14.0, 10.0, 4.0), "exactly equal counts as lethal (>=)");
        assertFalse(DamageUtil.wouldSelfKill(5.0, 10.0, 4.0), "5 self-damage vs 14 effective health is survivable");
    }
}
