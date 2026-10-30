package com.boss.pvp.module.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetainedAutoWeaponDecisionsTest {

    private static boolean charged(double ticker, double currentDelay, double weaponDelay) {
        return AutoWeaponModule.Decisions.charged(currentDelay, weaponDelay,
            offset -> Math.clamp((ticker + offset) / currentDelay, 0.0, 1.0));
    }

    @Test
    void saturatedFastHeldItemDoesNotPretendSlowSwapIsCharged() {
        assertFalse(charged(13.0, 12.5, 20.0));
        assertFalse(charged(19.999, 12.5, 20.0));
        assertTrue(charged(20.0, 12.5, 20.0));
        assertTrue(charged(80.0, 12.5, 20.0));
    }

    @Test
    void sameSpeedAndFasterSwapsUseTheSameActualTicker() {
        assertFalse(charged(12.0, 20.0, 12.5));
        assertTrue(charged(12.5, 20.0, 12.5));
        assertFalse(charged(19.0, 20.0, 20.0));
        assertTrue(charged(20.0, 20.0, 20.0));
    }

    @Test
    void invalidDelaysNeverProbeOrAuthorizeAnAttack() {
        assertFalse(AutoWeaponModule.Decisions.charged(0.0, 20.0, ignored -> fail("invalid current delay")));
        assertFalse(AutoWeaponModule.Decisions.charged(12.5, Double.POSITIVE_INFINITY, ignored -> fail("invalid weapon delay")));
        assertFalse(AutoWeaponModule.Decisions.charged(Double.NaN, 20.0, ignored -> fail("NaN delay")));
    }

    @Test
    void settlementWaitUsesActualWeaponDelayAndMonotonicNativeTickDuration() {
        assertEquals(14L * 50_000_000L + 100_000_000L,
            AutoWeaponModule.Decisions.settleNanos(12.5000001, 50_000_000L, 0, 100));
        assertEquals(14L * 100_000_000L + 100_000_000L,
            AutoWeaponModule.Decisions.settleNanos(12.5000001, 100_000_000L, 0, 100));
        assertEquals(14L * 50_000_000L + 300_000_000L,
            AutoWeaponModule.Decisions.settleNanos(12.5000001, 50_000_000L, 200, 100));
        assertEquals(AutoWeaponModule.Decisions.settleNanos(12.5, 50_000_000L, 0, 100),
            AutoWeaponModule.Decisions.settleNanos(12.5, 1L, -50, 100));
        assertEquals(Long.MAX_VALUE, AutoWeaponModule.Decisions.settleNanos(Double.NaN, 50_000_000L, 0, 100));
        assertEquals(Long.MAX_VALUE, AutoWeaponModule.Decisions.settleNanos(0.0, 50_000_000L, 0, 100));
        assertEquals(Long.MAX_VALUE, AutoWeaponModule.Decisions.settleNanos(12.5, 0L, 0, 100));
        assertEquals(Long.MAX_VALUE, AutoWeaponModule.Decisions.settleNanos(100.0, 50_000_000L, 0, 100));
        assertEquals(Long.MAX_VALUE, AutoWeaponModule.Decisions.settleNanos(12.5, 50_000_000L, 0, -1));
    }

    @Test
    void pendingIntentIsBoundedAndAttributesMustReallyMatch() {
        assertTrue(AutoWeaponModule.Decisions.pendingAge(0L));
        assertTrue(AutoWeaponModule.Decisions.pendingAge(3_000_000_000L));
        assertFalse(AutoWeaponModule.Decisions.pendingAge(3_000_000_001L));
        assertFalse(AutoWeaponModule.Decisions.pendingAge(-1L));
        assertTrue(AutoWeaponModule.Decisions.attributeMatches(1.5999999, 1.6));
        assertFalse(AutoWeaponModule.Decisions.attributeMatches(1.0, 7.0));
        assertFalse(AutoWeaponModule.Decisions.attributeMatches(Double.NaN, 7.0));
        assertFalse(AutoWeaponModule.Decisions.attributeMatches(7.0, Double.POSITIVE_INFINITY));
    }

    @Test
    void oneClickSelectsHighestDamageAndKeepsCurrentSlotOnTies() {
        double[] damage = {7.0, 10.0, 7.0, 10.0, 3.0, 1.0, 1.0, 1.0, 1.0};
        boolean[] shield = new boolean[9];
        assertEquals(1, AutoWeaponModule.Decisions.bestSlot(damage, shield, 0));
        assertEquals(3, AutoWeaponModule.Decisions.bestSlot(damage, shield, 3));
        damage[8] = Double.NaN;
        assertEquals(1, AutoWeaponModule.Decisions.bestSlot(damage, shield, 0));
    }

    @Test
    void realShieldDisablingCapabilityWinsWithoutAnArbitraryDamageBonus() {
        double[] damage = {40.0, 6.0, 8.0};
        assertEquals(2, AutoWeaponModule.Decisions.bestSlot(damage, new boolean[] {false, true, true}, 0));
        assertEquals(0, AutoWeaponModule.Decisions.bestSlot(damage, new boolean[3], 0));
    }

    @Test
    void mainInventoryWeaponsParticipateWhenTheCallerAllowsThem() {
        double[] damage = new double[36];
        damage[4] = 7.0;
        damage[27] = 20.0;
        assertEquals(27, AutoWeaponModule.Decisions.bestSlot(damage, new boolean[36], 4));
    }

    @Test
    void armorEffectivenessIsClampedRatherThanGrantingNegativeOrBonusDamage() {
        assertEquals(4.0, AutoWeaponModule.Decisions.remainingDamage(20.0, 0.8), 1.0e-8);
        assertEquals(16.0, AutoWeaponModule.Decisions.remainingDamage(20.0, 0.2), 1.0e-8);
        assertEquals(20.0, AutoWeaponModule.Decisions.remainingDamage(20.0, -0.4), 1.0e-8);
        assertEquals(0.0, AutoWeaponModule.Decisions.remainingDamage(20.0, 1.4), 1.0e-8);
    }

    @Test
    void restorationRequiresOwnershipUnchangedSelectionAndTheOriginalSession() {
        assertTrue(AutoWeaponModule.Decisions.restore(true, true, true, true, 6, 6, 2));
        assertFalse(AutoWeaponModule.Decisions.restore(false, true, true, true, 6, 6, 2));
        assertFalse(AutoWeaponModule.Decisions.restore(true, false, true, true, 6, 6, 2));
        assertFalse(AutoWeaponModule.Decisions.restore(true, true, false, true, 6, 6, 2));
        assertFalse(AutoWeaponModule.Decisions.restore(true, true, true, false, 6, 6, 2));
        assertFalse(AutoWeaponModule.Decisions.restore(true, true, true, true, 5, 6, 2));
        assertFalse(AutoWeaponModule.Decisions.restore(true, true, true, true, 6, 6, -1));
    }
}
