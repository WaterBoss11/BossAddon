package com.boss.pvp;

import com.boss.pvp.util.pvp.DamageUtil;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the effect-aware melee gate that guards HCsCR-style client-side crystal removal
 * ({@link DamageUtil#effectiveMeleeDamage} + {@link DamageUtil#meleeRegistersHit}). End crystals die to any
 * damage {@code > 0}, so a break only fails to register when the swing itself is nullified — in practice by
 * Weakness. Levels are 1-based (0 = effect absent); Strength adds +3/level, Weakness subtracts -4/level.
 */
class MeleeGateTest {

    private static final double EPS = 1e-9;

    @Test
    void noEffectsReturnsWeaponDamage() {
        assertEquals(6.0, DamageUtil.effectiveMeleeDamage(6.0, 0, 0), EPS);
    }

    @Test
    void strengthAddsThreePerLevel() {
        assertEquals(9.0, DamageUtil.effectiveMeleeDamage(6.0, 0, 1), EPS);
        assertEquals(12.0, DamageUtil.effectiveMeleeDamage(6.0, 0, 2), EPS);
    }

    @Test
    void weaknessSubtractsFourPerLevel() {
        assertEquals(2.0, DamageUtil.effectiveMeleeDamage(6.0, 1, 0), EPS); // sword under Weakness I
    }

    @Test
    void clampsAtZeroNeverNegative() {
        assertEquals(0.0, DamageUtil.effectiveMeleeDamage(1.0, 2, 0), EPS); // fist under Weakness II
    }

    @Test
    void registersWhenPositive() {
        assertTrue(DamageUtil.meleeRegistersHit(2.0));
        assertTrue(DamageUtil.meleeRegistersHit(0.001));
    }

    @Test
    void doesNotRegisterAtOrBelowZero() {
        assertFalse(DamageUtil.meleeRegistersHit(0.0));
        assertFalse(DamageUtil.meleeRegistersHit(-3.0));
    }

    @Test
    void fistUnderWeaknessOneWouldGhost() {
        // 1.0 fist, Weakness I -> 0 damage -> server ignores the swing -> must NOT hide (would ghost).
        double eff = DamageUtil.effectiveMeleeDamage(1.0, 1, 0);
        assertEquals(0.0, eff, EPS);
        assertFalse(DamageUtil.meleeRegistersHit(eff));
    }

    @Test
    void swordUnderWeaknessOneStillBreaks() {
        // 6.0 sword, Weakness I -> 2 damage -> crystal dies -> safe to hide.
        double eff = DamageUtil.effectiveMeleeDamage(6.0, 1, 0);
        assertTrue(DamageUtil.meleeRegistersHit(eff));
    }
}
