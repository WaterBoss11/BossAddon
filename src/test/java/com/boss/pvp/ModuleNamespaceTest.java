package com.boss.pvp;

import com.boss.utility.BossUtilityAddon;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the AUTISM namespace rule that silently broke every module after the boss-pvp &rarr;
 * bossaddon rename. AUTISM 3.4 rejects any addon module/HUD whose id does not start with {@code "<fabric mod
 * id>:"} ("foreign namespace"), so the module vanishes from the registry and menu entirely — which is why the
 * {@code ?bossaddon pvp|utility} toggle had nothing real to toggle. Our module ids are built as
 * {@code "<ID>:<local>"}, so each half's {@code ID} constant MUST begin with the mod id declared in
 * fabric.mod.json. This reads that id straight from the packaged fabric.mod.json and checks both halves against
 * it — exactly the mismatch (mod id {@code bossaddon} vs namespace {@code boss-pvp}) the crash log showed.
 *
 * <p>The {@code ID} fields are compile-time constants, so referencing them here inlines the value without loading
 * the client-dependent addon classes — this stays a pure, headless test.
 */
class ModuleNamespaceTest {

    /** The top-level mod id from the packaged fabric.mod.json (the first "id" key in the file). */
    private static String fabricModId() throws Exception {
        try (InputStream in = ModuleNamespaceTest.class.getResourceAsStream("/fabric.mod.json")) {
            assertNotNull(in, "fabric.mod.json must be on the test classpath");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
            assertTrue(m.find(), "fabric.mod.json must declare an id");
            return m.group(1);
        }
    }

    @Test
    void bothHalvesAreScopedUnderTheFabricModId() throws Exception {
        String modId = fabricModId();
        assertEquals("bossaddon", modId, "test is pinned to the current mod id");
        // A module id "<ID>:<local>" must start with "<modId>:" or AUTISM rejects it as a foreign namespace.
        assertTrue((BossPvpAddon.ID + ":x").startsWith(modId + ":"),
            "pvp module ids must be scoped under the mod id, got ID=" + BossPvpAddon.ID);
        assertTrue((BossUtilityAddon.ID + ":x").startsWith(modId + ":"),
            "utility module ids must be scoped under the mod id, got ID=" + BossUtilityAddon.ID);
    }

    @Test
    void halvesUseDistinctNonOverlappingNamespaces() {
        String pvp = BossPvpAddon.ID + ":";
        String util = BossUtilityAddon.ID + ":";
        assertNotEquals(pvp, util, "the two halves must be distinguishable by namespace");
        // Neither prefix may be a prefix of the other, or a filter for one half would also catch the other's
        // modules (both halves ship a flagreport module, so a collision would drop one of them).
        assertFalse(pvp.startsWith(util), "pvp namespace must not sit under the utility namespace");
        assertFalse(util.startsWith(pvp), "utility namespace must not sit under the pvp namespace");
    }
}
