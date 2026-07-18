package com.boss.pvp;

import com.boss.pvp.util.pvp.Gcd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure GCD (mouse-sensitivity quantization) math ported from LiquidBounce.
 *
 * The client-facing {@code liveStep()} / {@code normalize(Rotation, Rotation)} entry points read
 * {@code Minecraft.getInstance().options} and can't run headless (same limitation noted in
 * {@link RotationMathTest}). Everything below takes the sensitivity/step as a parameter and is
 * fully deterministic, so the core algorithm — the step formula and the grid-snapping — is covered.
 */
class GcdTest {

    private static final double EPS = 1.0e-6;
    private static final float FEPS = 1.0e-3f;

    // --- step: (sens*0.6 + 0.2)^3 * 8 * 0.15 ---

    @Test
    void testStepAtHalfSensitivity() {
        // sens 0.5 -> f=0.5 -> 0.125 * 8 * 0.15 = exactly 0.15 deg/unit (the canonical anchor value).
        assertEquals(0.15, Gcd.step(0.5), EPS, "step at 50% sensitivity is 0.15 degrees");
    }

    @Test
    void testStepAtZeroSensitivity() {
        // f=0.2 -> 0.008 * 8 * 0.15 = 0.0096
        assertEquals(0.0096, Gcd.step(0.0), EPS, "step at 0% sensitivity");
    }

    @Test
    void testStepAtFullSensitivity() {
        // f=0.8 -> 0.512 * 8 * 0.15 = 0.6144
        assertEquals(0.6144, Gcd.step(1.0), EPS, "step at 100% sensitivity");
    }

    @Test
    void testScopedStepDropsTheEightFactor() {
        // Scoping removes the *8, so the scoped step is exactly 1/8 of the normal step.
        assertEquals(Gcd.step(0.5) / 8.0, Gcd.step(0.5, true), EPS, "scoped step is 1/8 of normal");
        assertEquals(0.01875, Gcd.step(0.5, true), EPS, "scoped step at 50% sensitivity");
    }

    @Test
    void testStepStrictlyIncreasesWithSensitivity() {
        double prev = -1.0;
        for (double s = 0.0; s <= 1.0; s += 0.1) {
            double step = Gcd.step(s);
            assertTrue(step > prev, "step must grow with sensitivity");
            prev = step;
        }
    }

    // --- snapDelta: round a delta to the nearest whole multiple of the step ---

    @Test
    void testSnapDeltaRoundsToNearest() {
        double step = 0.15;
        assertEquals(0.0f, Gcd.snapDelta(0.07f, step), FEPS, "0.07 (< half a step) rounds down to 0");
        assertEquals(0.15f, Gcd.snapDelta(0.08f, step), FEPS, "0.08 (> half a step) rounds up to one step");
    }

    @Test
    void testSnapDeltaExactMultipleUnchanged() {
        assertEquals(0.30f, Gcd.snapDelta(0.30f, 0.15), FEPS, "an exact 2-step delta is preserved");
    }

    @Test
    void testSnapDeltaNegative() {
        assertEquals(-0.15f, Gcd.snapDelta(-0.15f, 0.15), FEPS, "negative deltas snap symmetrically");
    }

    @Test
    void testSnapDeltaZeroStepIsNoOp() {
        // step<=0 means sensitivity is unavailable; pass the delta through untouched rather than /0.
        assertEquals(5.0f, Gcd.snapDelta(5.0f, 0.0), FEPS, "step of 0 leaves the delta unchanged");
    }

    // --- angleDifference: shortest signed wrap ---

    @Test
    void testAngleDifferenceWraps() {
        assertEquals(20f, Math.abs(Gcd.angleDifference(10f, 350f)), FEPS, "wraps to the short 20-degree side");
    }

    // --- normalize: result differs from current only by whole grid steps ---

    @Test
    void testNormalizeDeltaIsWholeGridMultiple() {
        double step = 0.15;
        float[] r = Gcd.normalize(0f, 0f, 10f, 5f, step);
        double qYaw = (r[0] - 0f) / step;
        double qPitch = (r[1] - 0f) / step;
        assertEquals(Math.round(qYaw), qYaw, 1.0e-3, "yaw change must be a whole number of steps");
        assertEquals(Math.round(qPitch), qPitch, 1.0e-3, "pitch change must be a whole number of steps");
        // and it should land within half a step of the requested target
        assertTrue(Math.abs(r[0] - 10f) <= step, "snapped yaw is within one step of target");
        assertTrue(Math.abs(r[1] - 5f) <= step, "snapped pitch is within one step of target");
    }

    @Test
    void testNormalizeIsIdempotent() {
        double step = 0.15;
        float[] once = Gcd.normalize(0f, 0f, 10f, 5f, step);
        float[] twice = Gcd.normalize(0f, 0f, once[0], once[1], step);
        assertEquals(once[0], twice[0], FEPS, "re-normalizing an already-gridded yaw is a fixed point");
        assertEquals(once[1], twice[1], FEPS, "re-normalizing an already-gridded pitch is a fixed point");
    }

    @Test
    void testNormalizePitchIsClamped() {
        // A desired pitch that would push past 90 after snapping is clamped to the legal range.
        float[] r = Gcd.normalize(0f, 89f, 0f, 100f, 0.15);
        assertEquals(90f, r[1], FEPS, "pitch cannot exceed +90 degrees");
    }

    @Test
    void testNormalizeZeroStepReturnsRawTarget() {
        // With no usable sensitivity, aim isn't gridded — the desired rotation passes through (pitch clamped).
        float[] r = Gcd.normalize(0f, 0f, 33.3f, 12.7f, 0.0);
        assertEquals(33.3f, r[0], FEPS, "ungridded yaw is the raw target");
        assertEquals(12.7f, r[1], FEPS, "ungridded pitch is the raw target");
    }

    @Test
    void testNormalizeTracksTowardTarget() {
        // The snapped result should be much closer to the target than the starting rotation was.
        float[] r = Gcd.normalize(0f, 0f, 40f, 0f, 0.15);
        assertTrue(Math.abs(r[0] - 40f) < Math.abs(0f - 40f), "normalized yaw moves toward the target");
    }
}
