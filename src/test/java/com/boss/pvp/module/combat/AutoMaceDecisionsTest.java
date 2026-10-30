package com.boss.pvp.module.combat;

import com.boss.pvp.util.pvp.NativeAimBridge;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AutoMaceDecisionsTest {
    @Test
    void smashUsesNativeStrictFallBoundaryAndRealDownwardMovement() {
        assertFalse(AutoMaceModule.Decisions.fallWindow(1.5, -0.3, 1.5, 64.0, 0.05));
        assertTrue(AutoMaceModule.Decisions.fallWindow(1.50001, -0.3, 1.5, 64.0, 0.05));
        assertFalse(AutoMaceModule.Decisions.fallWindow(4.0, 0.3, 1.5, 64.0, 0.05));
        assertFalse(AutoMaceModule.Decisions.fallWindow(4.0, -0.05, 1.5, 64.0, 0.05));
        assertFalse(AutoMaceModule.Decisions.fallWindow(65.0, -1.0, 1.5, 64.0, 0.05));
        assertFalse(AutoMaceModule.Decisions.fallWindow(4.0, -1.0, 5.0, 4.0, 0.05));
        assertFalse(AutoMaceModule.Decisions.fallWindow(Double.NaN, -1.0, 1.5, 64.0, 0.05));
        assertFalse(AutoMaceModule.Decisions.fallWindow(4.0, Double.NEGATIVE_INFINITY, 1.5, 64.0, 0.05));
        assertFalse(AutoMaceModule.Decisions.fallWindow(4.0, -1.0, 1.0, 64.0, 0.05));
    }

    @Test
    void attributeSetupKeepsTheAxeUntilTheActualMaceStrikeWindow() {
        assertFalse(AutoMaceModule.Decisions.attributeSwapReady(false, true, true));
        assertFalse(AutoMaceModule.Decisions.attributeSwapReady(true, false, true));
        assertFalse(AutoMaceModule.Decisions.attributeSwapReady(true, true, false));
        assertTrue(AutoMaceModule.Decisions.attributeSwapReady(true, true, true));
    }

    @Test
    void nativeLungeRequiresTheEnchantAndVanillaHungerGate() {
        assertTrue(AutoMaceModule.Decisions.lungeEligible(1, 7, false, 7, false, false));
        assertFalse(AutoMaceModule.Decisions.lungeEligible(0, 20, false, 7, false, false));
        assertFalse(AutoMaceModule.Decisions.lungeEligible(1, 6, false, 7, false, false));
        assertFalse(AutoMaceModule.Decisions.lungeEligible(1, 12, false, 15, false, false));
        assertTrue(AutoMaceModule.Decisions.lungeEligible(3, 0, true, 7, false, false));
        assertFalse(AutoMaceModule.Decisions.lungeEligible(1, 20, false, 6, false, false));
        assertFalse(AutoMaceModule.Decisions.lungeEligible(1, 20, false, 21, false, false));
    }

    @Test
    void groundedLungesRequireTheirOwnOptIn() {
        assertFalse(AutoMaceModule.Decisions.lungeEligible(1, 20, false, 7, true, false));
        assertTrue(AutoMaceModule.Decisions.lungeEligible(1, 20, false, 7, true, true));
        assertTrue(AutoMaceModule.Decisions.lungeEligible(1, 20, false, 7, false, false));
    }

    @Test
    void explicitEnchantmentPreferenceHasNoArbitraryDamageWeightCeiling() {
        assertTrue(AutoMaceModule.Decisions.betterMace(1, 0, 7.0, 100_000.0));
        assertFalse(AutoMaceModule.Decisions.betterMace(0, 1, 100_000.0, 7.0));
        assertTrue(AutoMaceModule.Decisions.betterMace(0, 0, 10.0, 7.0));
        assertFalse(AutoMaceModule.Decisions.betterMace(0, 0, 7.0, 7.0));
        assertFalse(AutoMaceModule.Decisions.betterMace(3, 0, Double.NaN, 7.0));
    }

    @Test
    void sequenceDeadlineIsBoundedAndRejectsReversedClocks() {
        assertTrue(AutoMaceModule.Decisions.withinDeadline(1_500_000_000L, 1500));
        assertFalse(AutoMaceModule.Decisions.withinDeadline(1_500_000_001L, 1500));
        assertFalse(AutoMaceModule.Decisions.withinDeadline(-1L, 1500));
        assertFalse(AutoMaceModule.Decisions.withinDeadline(0L, 0));
    }

    @Test
    void nativeAimOptionsDoNotEscapeTheTickOrAffectOtherModules() {
        assertNull(NativeAimBridge.scopedValue("aim-assist", "range"));
        NativeAimBridge.withOptions(Map.of("range", "6.0", "horizontal-speed", "75"), () -> {
            assertEquals("6.0", NativeAimBridge.scopedValue("aim-assist", "range"));
            assertEquals("75", NativeAimBridge.scopedValue("aim-assist", "horizontal-speed"));
            assertNull(NativeAimBridge.scopedValue("kill-aura", "range"));
            assertNull(NativeAimBridge.scopedValue("bossaddon:pvp:automace", "range"));
            assertNull(NativeAimBridge.scopedValue("aim-assist", "unconfigured-option"));
            NativeAimBridge.withOptions(Map.of("range", "2.0"), () ->
                assertEquals("2.0", NativeAimBridge.scopedValue("aim-assist", "range")));
            assertEquals("6.0", NativeAimBridge.scopedValue("aim-assist", "range"));
        });
        assertNull(NativeAimBridge.scopedValue("aim-assist", "range"));
    }

    @Test
    void nativeAimScopeIsRestoredOnFailure() {
        assertThrows(IllegalStateException.class, () -> NativeAimBridge.withOptions(Map.of("range", "5.0"), () -> {
            throw new IllegalStateException("test failure");
        }));
        assertNull(NativeAimBridge.scopedValue("aim-assist", "range"));
        assertNull(NativeAimBridge.override(null, "range"));
    }
}
