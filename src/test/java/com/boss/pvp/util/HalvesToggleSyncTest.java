package com.boss.pvp.util;

import org.junit.jupiter.api.Test;

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
}
