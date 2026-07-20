package com.boss.pvp.flag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the combined (both-addons-installed) payload — one embed with a separate module field per addon,
 * the dual bot username, graceful "(unavailable)" when the BossUtility bridge couldn't be reached, and the
 * same sanitization as the individual embed.
 */
class FlagCombinedPayloadTest {

    private static final String LOGO = "https://example/logo.png";
    private static final String REPO = "WaterBoss11/BossAddon";
    private static final String ZWSP = "​";

    private static JsonObject embedOf(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    private static String fieldValue(JsonObject embed, String namePrefix) {
        JsonArray fields = embed.getAsJsonArray("fields");
        for (int i = 0; i < fields.size(); i++) {
            JsonObject f = fields.get(i).getAsJsonObject();
            if (f.get("name").getAsString().startsWith(namePrefix)) return f.get("value").getAsString();
        }
        throw new AssertionError("no field starting with " + namePrefix);
    }

    @Test
    void combinedHasBothModuleFieldsAndDualUsername() {
        String json = FlagPayload.buildCombined(FlagPayload.Type.KICKED, "kicked", "Notch",
            List.of("KillAura (COMBAT)", "AutoTotem (COMBAT)"), List.of("Sprint (MOVEMENT)"),
            "2026-07-17T00:00:00Z", LOGO, REPO);
        JsonObject payload = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("Boss Utility+PVP Flags", payload.get("username").getAsString());

        JsonObject embed = embedOf(json);
        assertEquals("Notch", fieldValue(embed, "Player"));
        String pvp = fieldValue(embed, "boss-pvp modules (2)");
        assertTrue(pvp.contains("KillAura (COMBAT)") && pvp.contains("AutoTotem (COMBAT)"));
        String util = fieldValue(embed, "BossUtility modules (1)");
        assertTrue(util.contains("Sprint (MOVEMENT)"));
    }

    @Test
    void nullUtilModulesShowsUnavailableStillOneReport() {
        String json = FlagPayload.buildCombined(FlagPayload.Type.CRASH, "npe", "Steve",
            List.of("AutoCrystal (COMBAT)"), null, "2026-07-17T00:00:00Z", LOGO, REPO);
        JsonObject embed = embedOf(json);
        assertEquals("*(unavailable)*", fieldValue(embed, "BossUtility modules (?)"));
        // boss-pvp side still present, and it's a single embed (one report, not two)
        assertTrue(fieldValue(embed, "boss-pvp modules (1)").contains("AutoCrystal (COMBAT)"));
        assertEquals(1, JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("embeds").size());
    }

    @Test
    void reasonAndModulesAreSanitized() {
        String json = FlagPayload.buildCombined(FlagPayload.Type.KICKED, "ping @everyone", "u",
            List.of("Mod @here (COMBAT)"), List.of(), "2026-07-17T00:00:00Z", LOGO, REPO);
        JsonObject embed = embedOf(json);
        assertFalse(embed.get("description").getAsString().contains("@everyone"));
        assertTrue(embed.get("description").getAsString().contains("@" + ZWSP + "everyone"));
        assertFalse(fieldValue(embed, "boss-pvp modules (1)").contains("@here"));
    }

    @Test
    void combinedEmbedHasNoLogField() {
        // The recent log now ships as a .txt attachment, so it must NOT appear as an embed field here either.
        String json = FlagPayload.buildCombined(FlagPayload.Type.KICKED, "kicked", "u",
            List.of("KillAura (COMBAT)"), List.of("Sprint (MOVEMENT)"),
            "2026-07-17T00:00:00Z", LOGO, REPO);
        JsonObject embed = embedOf(json);
        JsonArray fields = embed.getAsJsonArray("fields");
        for (int i = 0; i < fields.size(); i++) {
            assertFalse(fields.get(i).getAsJsonObject().get("name").getAsString().startsWith("Recent log"),
                "no Recent log field — the log is an attachment");
        }
    }

    @Test
    void emptyUtilListShowsCountZeroNotUnavailable() {
        String json = FlagPayload.buildCombined(FlagPayload.Type.KICKED, "r", "u",
            List.of("A (COMBAT)"), List.of(), "2026-07-17T00:00:00Z", LOGO, REPO);
        JsonObject embed = embedOf(json);
        // empty (bridge reachable, nothing enabled) is distinct from null (bridge unreachable)
        assertEquals("*(none enabled)*", fieldValue(embed, "BossUtility modules (0)"));
    }
}
