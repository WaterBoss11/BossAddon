package com.boss.pvp;

import com.boss.pvp.util.NumericSanity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct tests of the shared {@link NumericSanity} crash-guard checks, and of the exact predicate each packet
 * path evaluates. The mixin wiring itself needs a running client, but the numeric DECISION is pure: the
 * motion-packet path clamps via {@link NumericSanity#clampMotion}, and every position/velocity-carrying path
 * (explosion knockback, player/entity teleport, position sync, entity spawn) drops the packet when
 * {@link NumericSanity#isInsane(double, double, double)} is true. Real overflow-crash reports carried per-axis
 * values around 1.8e38 / 2.8e38 / 2.1e38, represented below.
 */
class NumericSanityTest {

    private static final double EXPLOIT_X = 1.8e38, EXPLOIT_Y = 2.8e38, EXPLOIT_Z = 2.1e38;

    // ---- core finite / insanity checks --------------------------------------------------------------------

    @Test
    void nonFiniteDetection() {
        assertTrue(NumericSanity.isNonFinite(Double.NaN));
        assertTrue(NumericSanity.isNonFinite(Double.POSITIVE_INFINITY));
        assertTrue(NumericSanity.isNonFinite(Double.NEGATIVE_INFINITY));
        assertFalse(NumericSanity.isNonFinite(0.0));
        assertFalse(NumericSanity.isNonFinite(-123.45));
        assertTrue(NumericSanity.isNonFinite(Float.NaN));
        assertTrue(NumericSanity.isNonFinite(Float.POSITIVE_INFINITY));
        assertFalse(NumericSanity.isNonFinite(9.5f));
    }

    @Test
    void isInsaneAgainstTheGeneralLimit() {
        double L = NumericSanity.SANE_LIMIT;
        assertFalse(NumericSanity.isInsane(0.0, L));
        assertFalse(NumericSanity.isInsane(100.0, L));
        assertFalse(NumericSanity.isInsane(2.9999984e7, L), "world-border coordinate is legitimate");
        assertFalse(NumericSanity.isInsane(L, L), "exactly at the limit is allowed (only > is insane)");
        assertTrue(NumericSanity.isInsane(Math.nextUp(L), L));
        assertTrue(NumericSanity.isInsane(Double.NaN, L));
        assertTrue(NumericSanity.isInsane(Double.POSITIVE_INFINITY, L));
        assertTrue(NumericSanity.isInsane(EXPLOIT_X, L));
        assertTrue(NumericSanity.isInsane(-EXPLOIT_Z, L));
    }

    // ---- motion-packet path: clampMotion (clamp-and-apply, keeps direction) --------------------------------

    @Test
    void motionPathClampsExtremesButKeepsRealSpeeds() {
        double cap = NumericSanity.MAX_MOTION_PER_AXIS;
        assertEquals(0.0, NumericSanity.clampMotion(Double.NaN), "NaN -> 0 (no direction to keep)");
        assertEquals(cap, NumericSanity.clampMotion(Double.POSITIVE_INFINITY));
        assertEquals(-cap, NumericSanity.clampMotion(Double.NEGATIVE_INFINITY));
        assertEquals(cap, NumericSanity.clampMotion(EXPLOIT_X), "1.8e38 -> cap");
        assertEquals(-cap, NumericSanity.clampMotion(-1.0e5));
        // Legitimate speeds (elytra/firework/riptide/TNT all < ~100) pass through bit-for-bit.
        for (double v : new double[]{0.0, 0.42, -3.92, 78.5, cap}) {
            assertEquals(v, NumericSanity.clampMotion(v), "legit speed untouched: " + v);
        }
    }

    // ---- drop paths: explosion knockback / player+entity teleport / position sync / entity spawn ----------

    @Test
    void positionTriplePassesLegitimateValues() {
        // A far-but-valid teleport near the world border, and a fast-but-valid entity velocity: NOT dropped.
        assertFalse(NumericSanity.isInsane(2.9e7, 320.0, -2.9e7), "near-border teleport is legit");
        assertFalse(NumericSanity.isInsane(0.0, 100.0, 0.0), "high but real velocity is legit");
        assertFalse(NumericSanity.isInsane(-8.5, 64.0, 12.25), "ordinary position is legit");
    }

    @Test
    void positionTripleCatchesMalformedAndExtremeValues() {
        // Malformed (NaN/Infinity) in any axis -> dropped.
        assertTrue(NumericSanity.isInsane(Double.NaN, 64.0, 0.0));
        assertTrue(NumericSanity.isInsane(0.0, Double.POSITIVE_INFINITY, 0.0));
        assertTrue(NumericSanity.isInsane(0.0, 0.0, Double.NEGATIVE_INFINITY));
        // The real crash values on every axis -> dropped.
        assertTrue(NumericSanity.isInsane(EXPLOIT_X, EXPLOIT_Y, EXPLOIT_Z));
        // Just one absurd axis past the general ceiling is enough.
        assertTrue(NumericSanity.isInsane(2.0e9, 64.0, 0.0));
        assertTrue(NumericSanity.isInsane(10.0, 5.0, -3.0e10));
    }

    @Test
    void explosionRadiusFloatIsChecked() {
        assertFalse(NumericSanity.isInsane(4.0f, NumericSanity.SANE_LIMIT), "a normal explosion radius is fine");
        assertTrue(NumericSanity.isInsane(Float.NaN, NumericSanity.SANE_LIMIT));
        assertTrue(NumericSanity.isInsane(Float.POSITIVE_INFINITY, NumericSanity.SANE_LIMIT));
        assertTrue(NumericSanity.isInsane(1.0e38f, NumericSanity.SANE_LIMIT), "overflow-scale radius is caught");
    }

    // ---- clamp() general contract -------------------------------------------------------------------------

    @Test
    void clampContract() {
        assertEquals(7.0, NumericSanity.clamp(Double.NaN, 100.0, 7.0), "NaN -> fallback");
        assertEquals(100.0, NumericSanity.clamp(Double.POSITIVE_INFINITY, 100.0, 7.0));
        assertEquals(-100.0, NumericSanity.clamp(Double.NEGATIVE_INFINITY, 100.0, 7.0));
        assertEquals(100.0, NumericSanity.clamp(1.0e30, 100.0, 7.0));
        assertEquals(42.5, NumericSanity.clamp(42.5, 100.0, 7.0), "in-range returned unchanged");
    }
}
