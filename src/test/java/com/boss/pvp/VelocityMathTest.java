package com.boss.pvp;

import com.boss.pvp.util.pvp.VelocityMath;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the pure knockback math behind VelocityModule ({@link VelocityMath}): percentage scaling used by
 * Modify/Lag/presets, the Reversal sign-flip, and the Strafe horizontal-speed + look-direction math.
 */
class VelocityMathTest {

    private static final double EPS = 1e-9;

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
