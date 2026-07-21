package com.boss.pvp;

import com.boss.pvp.relay.RelayManager;
import com.boss.pvp.relay.RelayManager.Mode;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the pure scope-switching logic behind the BossChat scope-tab bar ({@link RelayManager}): clicking a tab
 * activates that scope or toggles back OFF, the DM tab needs a remembered target, and each scope maps to a
 * distinct wire-scope string. UI rendering / the actual send are client-coupled and covered by an in-game look.
 */
class RelayScopeTest {

    @Test
    void scopeTabActivatesOrTogglesOff() {
        assertEquals(Mode.GLOBAL, RelayManager.nextScope(Mode.OFF, Mode.GLOBAL));    // off -> click GLOBAL -> global
        assertEquals(Mode.SERVER, RelayManager.nextScope(Mode.GLOBAL, Mode.SERVER)); // switch between scopes
        assertEquals(Mode.PARTY,  RelayManager.nextScope(Mode.OFF, Mode.PARTY));
        assertEquals(Mode.OFF,    RelayManager.nextScope(Mode.GLOBAL, Mode.GLOBAL)); // click the active tab -> off
        assertEquals(Mode.OFF,    RelayManager.nextScope(Mode.PARTY, Mode.PARTY));
    }

    @Test
    void dmTabNeedsATargetAndTogglesOff() {
        assertEquals(Mode.DM,     RelayManager.nextDmMode(Mode.OFF, true));    // has a target -> activate DM
        assertEquals(Mode.OFF,    RelayManager.nextDmMode(Mode.DM, true));     // click active DM -> off
        assertEquals(Mode.OFF,    RelayManager.nextDmMode(Mode.DM, false));    // active DM toggles off either way
        assertEquals(Mode.GLOBAL, RelayManager.nextDmMode(Mode.GLOBAL, false));// no target -> no change (caller hints)
        assertEquals(Mode.SERVER, RelayManager.nextDmMode(Mode.SERVER, false));
    }

    @Test
    void wireScopeMapsEveryMode() {
        assertEquals("global", RelayManager.wireScope(Mode.GLOBAL));
        assertEquals("server", RelayManager.wireScope(Mode.SERVER));
        assertEquals("party",  RelayManager.wireScope(Mode.PARTY));
        assertEquals("dm",     RelayManager.wireScope(Mode.DM));
        assertEquals("global", RelayManager.wireScope(Mode.OFF));   // OFF never sends; safe default
    }

    @Test
    void theFourTabScopesAreDistinct() {
        String[] tabs = {
            RelayManager.wireScope(Mode.GLOBAL), RelayManager.wireScope(Mode.SERVER),
            RelayManager.wireScope(Mode.PARTY),  RelayManager.wireScope(Mode.DM),
        };
        assertEquals(4, Arrays.stream(tabs).distinct().count(), "GLOBAL/SERVER/PARTY/DM must be four distinct scopes");
    }
}
