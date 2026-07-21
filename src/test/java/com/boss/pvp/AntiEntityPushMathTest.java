package com.boss.pvp;

import com.boss.pvp.module.combat.AntiEntityPushModule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Tests the pure push-handling math of {@link AntiEntityPushModule#pushDelta} — the velocity that AntiEntityPush
 * actually applies for a raw incoming push. "Cancel" must stay the original full-block (zero) behaviour;
 * "Modify" must scale each axis by the keep-percentage (reusing VelocityMath's percentage math).
 */
class AntiEntityPushMathTest {

    private static final double EPS = 1e-9;

    @Test
    void cancelModeBlocksEverything() {
        // Cancel is the default and unchanged from the module's original behaviour: no push is applied at all,
        // regardless of the (irrelevant) keep percentages.
        assertArrayEquals(new double[]{0.0, 0.0, 0.0},
            AntiEntityPushModule.pushDelta("Cancel", 10.0, 2.0, 6.0, 40, 100), EPS);
        assertArrayEquals(new double[]{0.0, 0.0, 0.0},
            AntiEntityPushModule.pushDelta("Cancel", -3.5, -1.0, 7.2, 100, 100), EPS, "percentages ignored in Cancel");
    }

    @Test
    void modifyKeepsHorizontalOnXZAndVerticalOnY() {
        // keep 40% horizontal, 100% vertical: x and z -> 40%, y unchanged.
        assertArrayEquals(new double[]{4.0, 2.0, 2.4},
            AntiEntityPushModule.pushDelta("Modify", 10.0, 2.0, 6.0, 40, 100), EPS);
    }

    @Test
    void modifyZeroPercentEqualsCancel_HundredEqualsUnchanged() {
        // 0% keep on every axis is equivalent to a full cancel...
        assertArrayEquals(new double[]{0.0, 0.0, 0.0},
            AntiEntityPushModule.pushDelta("Modify", 10.0, 2.0, 6.0, 0, 0), EPS);
        // ...and 100% keep leaves the push completely unchanged.
        assertArrayEquals(new double[]{10.0, 2.0, 6.0},
            AntiEntityPushModule.pushDelta("Modify", 10.0, 2.0, 6.0, 100, 100), EPS);
    }

    @Test
    void modifyPreservesDirectionOnNegativeComponents() {
        // Reducing a push must not flip its direction — a 50% keep just halves the magnitude, sign intact.
        assertArrayEquals(new double[]{-4.0, -1.0, 3.0},
            AntiEntityPushModule.pushDelta("Modify", -8.0, -2.0, 6.0, 50, 50), EPS);
    }
}
