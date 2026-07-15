package com.boss.pvp;

import com.boss.pvp.util.pvp.PvpUtil;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the team-check colour-tolerance math ({@code PvpUtil.colorsMatch}) — the load-bearing part of
 * {@link PvpUtil#isTeammate}: two dyed-leather RGB colours count as a match when every channel is within ±15.
 *
 * The full {@code isTeammate(LocalPlayer, Player)} path needs two live player entities (armor slots + dyed
 * components) and a Minecraft world, which isn't available in a plain unit test — so we exercise the pure
 * colour-comparison helper directly via reflection (read-only; no module change). The ≥2-slots requirement and
 * dyed-vs-undyed detection live in the entity-coupled parts and are covered by the design, not by these tests.
 */
class TeamCheckTest {

    private static boolean colorsMatch(int a, int b) {
        try {
            Method m = PvpUtil.class.getDeclaredMethod("colorsMatch", int.class, int.class);
            m.setAccessible(true);
            return (boolean) m.invoke(null, a, b);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("could not access PvpUtil.colorsMatch(int,int)", e);
        }
    }

    @Test
    void testExactColorMatches() {
        assertTrue(colorsMatch(0xFF0000, 0xFF0000), "identical colours match");
    }

    @Test
    void testWithinToleranceMatches() {
        // R: |255-240|=15, G: |0-10|=10, B: |0-10|=10 — all <= 15.
        assertTrue(colorsMatch(0xFF0000, 0xF00A0A), "colours within +-15 on every channel match");
    }

    @Test
    void testExactlyFifteenIsInclusiveMatch() {
        // R: |128-143| = 15 exactly — the boundary is inclusive.
        assertTrue(colorsMatch(0x800000, 0x8F0000), "a 15-per-channel difference still matches (inclusive)");
    }

    @Test
    void testSixteenMissesOnOneChannel() {
        // R: |128-144| = 16 — over tolerance -> no match.
        assertFalse(colorsMatch(0x800000, 0x900000), "a 16 difference on one channel breaks the match");
    }

    @Test
    void testLargeSingleChannelDifferenceMisses() {
        // B: |0-20| = 20 — one channel far apart is enough to fail even if others match.
        assertFalse(colorsMatch(0xFF0000, 0xFF0014), "a 20 difference on one channel is not a teammate colour");
    }

    @Test
    void testAllChannelsAtBoundaryInclusive() {
        // Every channel differs by exactly 15 (128 vs 143) — inclusive boundary, still a match.
        assertTrue(colorsMatch(0x808080, 0x8F8F8F), "15 on every channel is within tolerance (inclusive)");
    }

    @Test
    void testAllChannelsOverBoundaryMiss() {
        // Every channel differs by 16 (128 vs 144) — over tolerance, no match.
        assertFalse(colorsMatch(0x808080, 0x909090), "16 on every channel exceeds the ±15 tolerance");
    }
}
