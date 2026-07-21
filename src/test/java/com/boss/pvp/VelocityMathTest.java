package com.boss.pvp;

import com.boss.pvp.util.pvp.VelocityMath;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure knockback math behind VelocityModule ({@link VelocityMath}): percentage scaling used by
 * Modify/Lag/presets, the Reversal sign-flip, the Strafe horizontal-speed + look-direction math, and the
 * incoming-velocity crash-guard clamp.
 */
class VelocityMathTest {

    private static final double EPS = 1e-9;

    // The exact per-axis momentum from the two real crash reports (same values on both accounts).
    private static final double CRASH_X = 1.8e38;
    private static final double CRASH_Y = 2.8e38;
    private static final double CRASH_Z = 2.1e38;

    private static final double MAX = VelocityMath.MAX_MOTION_PER_AXIS;

    @Test
    void clampLeavesNormalMotionUntouched() {
        // Everything a real game ever produces is orders of magnitude below the cap and must pass through
        // bit-for-bit — the guard must never alter legitimate speed.
        for (double v : new double[]{0.0, -0.0, 0.08, -0.3, 3.92, -3.92, 42.0, -99.5, 500.0, -1234.5}) {
            assertEquals(v, VelocityMath.clampMotion(v), 0.0, "in-range value must be unchanged: " + v);
        }
        // Exactly at the cap is still in range.
        assertEquals(MAX, VelocityMath.clampMotion(MAX), 0.0);
        assertEquals(-MAX, VelocityMath.clampMotion(-MAX), 0.0);
        assertFalse(VelocityMath.needsClamp(0.08, -3.92, 42.0), "normal elytra/fall speeds don't need clamping");
        assertFalse(VelocityMath.needsClamp(MAX, -MAX, 0.0), "boundary values are in range");
    }

    @Test
    void clampCapsTheExactRealCrashValues() {
        // The precise values that crashed the client in vanilla EntitySectionStorage/collision code.
        assertEquals(MAX, VelocityMath.clampMotion(CRASH_X), 0.0);
        assertEquals(MAX, VelocityMath.clampMotion(CRASH_Y), 0.0);
        assertEquals(MAX, VelocityMath.clampMotion(CRASH_Z), 0.0);
        // ...and their negatives (a push in the other direction is just as fatal).
        assertEquals(-MAX, VelocityMath.clampMotion(-CRASH_X), 0.0);
        assertEquals(-MAX, VelocityMath.clampMotion(-CRASH_Y), 0.0);
        assertEquals(-MAX, VelocityMath.clampMotion(-CRASH_Z), 0.0);
        assertTrue(VelocityMath.needsClamp(CRASH_X, CRASH_Y, CRASH_Z), "the real crash vector must be clamped");
    }

    @Test
    void clampNeutralisesNonFiniteValues() {
        // A malicious server can also send NaN/Infinity; those must never reach vanilla's math.
        assertEquals(0.0, VelocityMath.clampMotion(Double.NaN), 0.0, "NaN has no direction -> 0");
        assertEquals(MAX, VelocityMath.clampMotion(Double.POSITIVE_INFINITY), 0.0);
        assertEquals(-MAX, VelocityMath.clampMotion(Double.NEGATIVE_INFINITY), 0.0);
        assertEquals(MAX, VelocityMath.clampMotion(Double.MAX_VALUE), 0.0);
        assertTrue(VelocityMath.needsClamp(Double.NaN, 0.0, 0.0), "any NaN axis needs clamping");
        assertTrue(VelocityMath.needsClamp(0.0, Double.POSITIVE_INFINITY, 0.0), "any infinite axis needs clamping");
    }

    @Test
    void scaleIsPercentOfValue() {
        assertEquals(10.0, VelocityMath.scale(10.0, 100), EPS);   // keep 100% = unchanged
        assertEquals(0.0, VelocityMath.scale(10.0, 0), EPS);      // keep 0% = cancelled
        assertEquals(4.0, VelocityMath.scale(10.0, 40), EPS);     // Modify default horizontal keep
        assertEquals(20.0, VelocityMath.scale(10.0, 200), EPS);   // >100% amplifies (Strafe strength)
    }

    @Test
    void reverseFlipsSignAndScales() {
        assertEquals(-5.0, VelocityMath.reverse(5.0, 100), EPS);
        assertEquals(3.0, VelocityMath.reverse(-3.0, 100), EPS);  // reversing negative motion pushes positive
        assertEquals(-2.0, VelocityMath.reverse(4.0, 50), EPS);   // half-strength reversal
        assertEquals(0.0, VelocityMath.reverse(5.0, 0), EPS);
    }

    @Test
    void horizontalSpeedIsPythagorean() {
        assertEquals(5.0, VelocityMath.horizontalSpeed(3.0, 4.0), EPS);
        assertEquals(0.0, VelocityMath.horizontalSpeed(0.0, 0.0), EPS);
    }

    @Test
    void lookDirectionMatchesVanillaYaw() {
        // yaw 0 -> +Z (south); yaw 90 -> -X (west); yaw 180 -> -Z (north).
        assertEquals(0.0, VelocityMath.dirX(0f), 1e-9);
        assertEquals(1.0, VelocityMath.dirZ(0f), 1e-9);
        assertEquals(-1.0, VelocityMath.dirX(90f), 1e-9);
        assertEquals(0.0, VelocityMath.dirZ(90f), 1e-9);
        assertEquals(0.0, VelocityMath.dirX(180f), 1e-9);
        assertEquals(-1.0, VelocityMath.dirZ(180f), 1e-9);
    }
}
