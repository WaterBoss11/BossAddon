package com.boss.pvp.relay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The relay scope state machine (pure — no client needed). The toggle button cycles OFF → GLOBAL → SERVER →
 * OFF, and the button label reflects the current scope. (Connection/auth-dependent behaviour is not exercised
 * here — it needs a live socket; see the live-verification list.)
 */
class RelayModeTest {

    @Test
    void cycleWrapsThroughAllThreeScopes() {
        RelayManager r = RelayManager.get();
        r.setMode(RelayManager.Mode.OFF);
        r.cycleMode(); assertEquals(RelayManager.Mode.GLOBAL, r.mode());
        r.cycleMode(); assertEquals(RelayManager.Mode.SERVER, r.mode());
        r.cycleMode(); assertEquals(RelayManager.Mode.OFF, r.mode());
    }

    @Test
    void buttonLabelReflectsScope() {
        RelayManager r = RelayManager.get();
        r.setMode(RelayManager.Mode.OFF);
        assertEquals("Relay: off", r.buttonLabel());
        r.setMode(RelayManager.Mode.GLOBAL);
        // Not authed in a unit test, so the label carries the "connecting" ellipsis.
        assertTrue(r.buttonLabel().startsWith("Relay: GLOBAL"));
    }

    @Test
    void offModeNeverRedirectsChat() {
        RelayManager r = RelayManager.get();
        r.setMode(RelayManager.Mode.OFF);
        assertTrue(!r.shouldRedirectChat(), "OFF must never eat typed chat");
    }
}
