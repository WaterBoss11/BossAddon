package com.boss.pvp.relay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relay scope state machine (pure — no client needed). The toggle button cycles OFF → GLOBAL → SERVER →
 * OFF, and the button label reflects the current scope. (Connection/auth-dependent behaviour is not exercised
 * here — it needs a live socket; see the live-verification list.)
 */
class RelayModeTest {

    @Test
    void cycleWrapsThroughAllScopesIncludingParty() {
        RelayManager r = RelayManager.get();
        r.setMode(RelayManager.Mode.OFF);
        r.cycleMode(); assertEquals(RelayManager.Mode.GLOBAL, r.mode());
        r.cycleMode(); assertEquals(RelayManager.Mode.SERVER, r.mode());
        r.cycleMode(); assertEquals(RelayManager.Mode.PARTY, r.mode());
        r.cycleMode(); assertEquals(RelayManager.Mode.OFF, r.mode());
    }

    @Test
    void buttonLabelIsBrandedBossChatWithScopeAndDot() {
        RelayManager r = RelayManager.get();
        r.setMode(RelayManager.Mode.OFF);
        String off = r.buttonLabel();
        assertTrue(off.contains("BossChat:"), "branded BossChat, not Relay");
        assertTrue(off.contains("off"));
        assertTrue(off.contains("●"), "has the status dot icon");
        assertTrue(off.contains("§7"), "off state is grey");
        assertFalse(off.contains("Relay"), "old 'Relay' label must be gone");

        r.setMode(RelayManager.Mode.GLOBAL);
        String global = r.buttonLabel();
        assertTrue(global.contains("BossChat:") && global.contains("global"));
        // Not authed in a unit test, so the connecting-state colour (§e) is used.
        assertTrue(global.contains("§e"), "connecting state is yellow");
    }

    @Test
    void offModeNeverRedirectsChat() {
        RelayManager r = RelayManager.get();
        r.setMode(RelayManager.Mode.OFF);
        assertTrue(!r.shouldRedirectChat(), "OFF must never eat typed chat");
    }
}
