package com.boss.pvp;

import com.boss.pvp.util.pvp.AttackCharge;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the attribute-swap charge math ({@link AttackCharge}) that backs AutoWeapon's full-charge gate.
 *
 * <p>Reference attack speeds/delays used below: sword ATTACK_SPEED 1.6 -> delay 12.5 ticks; axe ATTACK_SPEED 1.0
 * -> delay 20 ticks. The shared ticker is recovered from the currently held item as {@code currentScale *
 * currentDelay} while that item is unsaturated.
 */
class AttackChargeTest {

    private static final double EPS = 1e-9;

    @Test
    void attackSpeedToDelayMatchesVanillaFormula() {
        assertEquals(12.5, AttackCharge.attackSpeedToDelay(1.6), EPS); // sword
        assertEquals(20.0, AttackCharge.attackSpeedToDelay(1.0), EPS); // axe
        assertEquals(10.0, AttackCharge.attackSpeedToDelay(2.0), EPS);
    }

    @Test
    void attackSpeedToDelayGuardsNonPositiveSpeed() {
        assertEquals(Double.POSITIVE_INFINITY, AttackCharge.attackSpeedToDelay(0.0), EPS);
        assertEquals(Double.POSITIVE_INFINITY, AttackCharge.attackSpeedToDelay(-1.0), EPS);
    }

    @Test
    void chargeScaleIsTickerOverDelay() {
        assertEquals(0.5, AttackCharge.chargeScale(10.0, 20.0), EPS);
        assertEquals(1.0, AttackCharge.chargeScale(20.0, 20.0), EPS);
        assertEquals(1.6, AttackCharge.chargeScale(20.0, 12.5), EPS); // over-full ticker stays raw, not clamped
    }

    @Test
    void chargeScaleTreatsNonPositiveDelayAsCharged() {
        assertEquals(1.0, AttackCharge.chargeScale(0.0, 0.0), EPS); // bare hand / infinite attack speed
    }

    @Test
    void isChargedAtExactlyFull() {
        assertTrue(AttackCharge.isCharged(12.5, 12.5));
        assertFalse(AttackCharge.isCharged(12.49, 12.5));
    }

    // --- bestWeaponCharged: the actual gate decision ---

    @Test
    void unsaturatedSwapToSlowerWeaponWaits() {
        // Holding a sword nearly charged (scale 0.9 * 12.5 = ticker 11.25), swapping to an axe (delay 20):
        // 11.25 / 20 = 0.5625 -> NOT charged. This is the bug fix: the old current-item gate would have fired.
        assertFalse(AttackCharge.bestWeaponCharged(0.9, 12.5, 20.0));
    }

    @Test
    void unsaturatedSwapToFasterWeaponReadyEarly() {
        // Holding an axe 80% charged (scale 0.8 * 20 = ticker 16), swapping to a sword (delay 12.5):
        // 16 / 12.5 = 1.28 -> charged before the slow current item finishes. Legitimately fires earlier.
        assertTrue(AttackCharge.bestWeaponCharged(0.8, 20.0, 12.5));
    }

    @Test
    void unsaturatedSameSpeedMatchesCurrentItem() {
        // Same weapon in hand and as swap target: gate collapses to the plain current-item charge.
        assertFalse(AttackCharge.bestWeaponCharged(0.99, 12.5, 12.5));
        assertTrue(AttackCharge.bestWeaponCharged(1.0, 12.5, 12.5));
    }

    @Test
    void unsaturatedExactBoundaryIsCharged() {
        // scale 0.625 * currentDelay 20 = ticker 12.5, exactly the sword's delay -> charged.
        assertTrue(AttackCharge.bestWeaponCharged(0.625, 20.0, 12.5));
    }

    @Test
    void saturatedCurrentItemIsOptimisticallyReady() {
        // Documents the residual window: a fully-charged fast current item is treated as ready even for a slower
        // swap target, because the raw ticker (>= currentDelay) isn't recoverable once the scale clamps at 1.0.
        // This exactly reproduces the legacy fullCharge behaviour and avoids a hard stall.
        assertTrue(AttackCharge.bestWeaponCharged(1.0, 12.5, 20.0));
        assertTrue(AttackCharge.bestWeaponCharged(1.0, 20.0, 12.5));
    }
}
