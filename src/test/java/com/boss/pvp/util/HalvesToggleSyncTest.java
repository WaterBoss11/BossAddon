package com.boss.pvp.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Part 3 regression: {@code ?bossaddon pvp|utility on|off} must actually flip each module's enabled state — the
 * same state AUTISM's menu renders from — not just an internal half flag. The real path calls AUTISM's
 * {@code setConfiguredEnabled} (verified by decompilation to be exactly what the menu uses); here we drive
 * {@link AddonHalves#setHalf} through a fake {@link AddonHalves.ModuleView} and assert the module's enabled
 * state is toggled and restored correctly, which is the behaviour that was out of sync.
 */
class HalvesToggleSyncTest {

    /** A fake module whose {@code enabled} flag stands in for AUTISM's own per-module state. */
    private static final class FakeModule implements AddonHalves.ModuleView {
        final String id;
        boolean enabled;
        int enables, disables;
        FakeModule(String id, boolean enabled) { this.id = id; this.enabled = enabled; }
        @Override public String id() { return id; }
        @Override public boolean isEnabled() { return enabled; }
        @Override public void setEnabled(boolean on) { enabled = on; if (on) enables++; else disables++; }
    }

    @BeforeEach
    void resetHalves() {
        // Default source = AUTISM's live registry, which is unreachable headlessly and falls back to the
        // registered arrays — so each test starts from empty registered halves unless it sets its own source.
        AddonHalves.setModuleSource(null);
        AddonHalves.registerViews(AddonHalves.PVP, new AddonHalves.ModuleView[0]);
        AddonHalves.registerViews(AddonHalves.UTILITY, new AddonHalves.ModuleView[0]);
    }

    @AfterEach
    void restoreSource() {
        // Never leak a fake source into other test classes that touch AddonHalves.
        AddonHalves.setModuleSource(null);
    }

    @Test
    void offDisablesAndOnRestoresEnabledState() {
        FakeModule a = new FakeModule("bosstest:a", true);
        FakeModule b = new FakeModule("bosstest:b", true);
        AddonHalves.registerViews(AddonHalves.PVP, new AddonHalves.ModuleView[]{a, b});
        assertTrue(AddonHalves.pvpOn(), "half starts on");

        AddonHalves.setHalf(AddonHalves.PVP, false);
        assertFalse(a.enabled, "A disabled after pvp off");
        assertFalse(b.enabled, "B disabled after pvp off");
        assertFalse(AddonHalves.pvpOn());

        AddonHalves.setHalf(AddonHalves.PVP, true);
        assertTrue(a.enabled, "A re-enabled after pvp on");
        assertTrue(b.enabled, "B re-enabled after pvp on");
        assertTrue(AddonHalves.pvpOn());
    }

    @Test
    void onRestoresOnlyModulesThatWereEnabled() {
        FakeModule on = new FakeModule("bosstest:on", true);
        FakeModule off = new FakeModule("bosstest:off", false);
        AddonHalves.registerViews(AddonHalves.PVP, new AddonHalves.ModuleView[]{on, off});

        AddonHalves.setHalf(AddonHalves.PVP, false);   // snapshots {on}, disables all
        assertFalse(on.enabled);
        assertFalse(off.enabled);

        AddonHalves.setHalf(AddonHalves.PVP, true);    // restores only the previously-enabled {on}
        assertTrue(on.enabled, "the enabled one comes back");
        assertFalse(off.enabled, "the one that was off stays off");
    }

    @Test
    void toggleOnlyTouchesModulesThatActuallyChange() {
        FakeModule a = new FakeModule("bosstest:c", true);
        AddonHalves.registerViews(AddonHalves.PVP, new AddonHalves.ModuleView[]{a});
        AddonHalves.setHalf(AddonHalves.PVP, false);
        AddonHalves.setHalf(AddonHalves.PVP, true);
        // Exactly one disable (off) and one enable (restore) — no redundant setEnabled churn.
        assertTrue(a.disables == 1 && a.enables == 1, "one disable + one enable, got d=" + a.disables + " e=" + a.enables);

        // A redundant toggle to the same state is a no-op (the half was already on).
        String msg = AddonHalves.setHalf(AddonHalves.PVP, true);
        assertTrue(msg != null && msg.contains("already on"), "redundant on -> already on: " + msg);
    }

    @Test
    void parseSavedRoundTrips() {
        assertTrue(AddonHalves.parseSaved("").isEmpty());
        assertTrue(AddonHalves.parseSaved(null).isEmpty());
        assertTrue(AddonHalves.parseSaved("x:a,x:b").containsAll(java.util.List.of("x:a", "x:b")));
    }

    // ---- The utility-half "0 modules" regression -----------------------------------------------------------
    // The bug: the utility half enumerated an empty array (it relied on registerUtility having populated a cache),
    // so `?bossaddon utility on|off` silently toggled nothing while reporting success. The fix enumerates each
    // half from AUTISM's LIVE module registry filtered by id-namespace (bossaddon:pvp: / bossaddon:utility:). These tests
    // register NOTHING for the halves (mirroring the broken state) and prove enumeration still finds each half's
    // own modules from the live registry. The older tests above only ever registered the PVP half by hand and
    // never asserted a non-zero count, which is exactly why they missed this.

    @Test
    void eachHalfEnumeratesItsOwnNamespaceFromLiveRegistryNotAnEmptyArray() {
        FakeModule pvpKa   = new FakeModule("bossaddon:pvp:killaura", true);
        FakeModule pvpAc   = new FakeModule("bossaddon:pvp:autocrystal", false);
        FakeModule uSprint = new FakeModule("bossaddon:utility:sprint", true);
        FakeModule uStep   = new FakeModule("bossaddon:utility:step", false);
        FakeModule uAfk    = new FakeModule("bossaddon:utility:antiafk", true);
        FakeModule autism  = new FakeModule("killaura", true);   // AUTISM's own module, neither half
        // Note: NO registerViews for either half — the cached arrays are empty, as in the bug.
        AddonHalves.setModuleSource(() -> List.of(pvpKa, pvpAc, uSprint, uStep, uAfk, autism));

        List<String> util = AddonHalves.moduleIds(AddonHalves.UTILITY);
        assertFalse(util.isEmpty(), "utility half must not enumerate zero modules");
        assertEquals(List.of("bossaddon:utility:sprint", "bossaddon:utility:step", "bossaddon:utility:antiafk"), util,
            "utility half is exactly the bossaddon:utility: namespace");

        assertEquals(List.of("bossaddon:pvp:killaura", "bossaddon:pvp:autocrystal"),
            AddonHalves.moduleIds(AddonHalves.PVP), "pvp half is exactly the bossaddon:pvp: namespace");

        // AUTISM's own modules belong to neither half.
        assertFalse(util.contains("killaura"));
        assertFalse(AddonHalves.moduleIds(AddonHalves.PVP).contains("killaura"));
    }

    @Test
    void utilityToggleOffThenOnActsOnItsLiveRegistryModules() {
        FakeModule uSprint = new FakeModule("bossaddon:utility:sprint", true);
        FakeModule uStep   = new FakeModule("bossaddon:utility:step", true);
        FakeModule uAfk    = new FakeModule("bossaddon:utility:antiafk", false);   // was off; must stay off after restore
        if (!AddonHalves.utilityOn()) AddonHalves.setHalf(AddonHalves.UTILITY, true);   // known ON baseline
        AddonHalves.setModuleSource(() -> List.of(uSprint, uStep, uAfk));

        String off = AddonHalves.setHalf(AddonHalves.UTILITY, false);
        assertTrue(off.contains("disabled 2"), "off disables the 2 enabled utility modules: " + off);
        assertFalse(uSprint.enabled);
        assertFalse(uStep.enabled);
        assertFalse(uAfk.enabled);

        String on = AddonHalves.setHalf(AddonHalves.UTILITY, true);
        assertTrue(on.contains("restored 2"), "on restores exactly the 2 that were enabled: " + on);
        assertTrue(uSprint.enabled, "sprint comes back");
        assertTrue(uStep.enabled, "step comes back");
        assertFalse(uAfk.enabled, "the one that was off stays off");
    }
}
