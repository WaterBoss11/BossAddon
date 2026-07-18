package com.boss.pvp.flag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure flag-payload builder: valid JSON out, per-type colour, and — the important part —
 * injection safety (control chars stripped, mass mentions neutralized, everything serialized by Gson rather
 * than concatenated), plus module-list capping and field truncation.
 */
class FlagPayloadTest {

    private static final String LOGO = "https://example/logo.png";
    private static final String REPO = "WaterBoss11/boss-pvp";
    private static final String ZWSP = "​";

    private static JsonObject buildParsed(FlagPayload.Type type, String reason, List<String> modules) {
        String json = FlagPayload.build(type, reason, modules, "2026-07-17T00:00:00Z", LOGO, REPO);
        return JsonParser.parseString(json).getAsJsonObject(); // throws if not valid JSON
    }

    private static JsonObject embedOf(JsonObject payload) {
        return payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    @Test
    void producesValidJsonWithUsernameAndEmbed() {
        JsonObject p = buildParsed(FlagPayload.Type.KICK, "You were kicked", List.of("KillAura (COMBAT)"));
        assertEquals("Boss PVP Flags", p.get("username").getAsString());
        assertEquals(1, p.getAsJsonArray("embeds").size());
    }

    @Test
    void colorMatchesType() {
        assertEquals(0xE67E22, embedOf(buildParsed(FlagPayload.Type.KICK, "r", List.of())).get("color").getAsInt());
        assertEquals(0xD35400, embedOf(buildParsed(FlagPayload.Type.PACKET_KICK, "r", List.of())).get("color").getAsInt());
        assertEquals(0xC0392B, embedOf(buildParsed(FlagPayload.Type.CRASH, "r", List.of())).get("color").getAsInt());
    }

    @Test
    void controlCharsStrippedFromReason() {
        // bell (0x07) and a raw ESC (0x1B) inside the reason must be removed; printable text survives.
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.KICK, "badreason", List.of()));
        assertEquals("badreason", embed.get("description").getAsString());
    }

    @Test
    void massMentionsNeutralized() {
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.KICK, "ping @everyone and @here", List.of()));
        String desc = embed.get("description").getAsString();
        assertFalse(desc.contains("@everyone"), "raw @everyone must not survive");
        assertFalse(desc.contains("@here"), "raw @here must not survive");
        assertTrue(desc.contains("@" + ZWSP + "everyone"));
    }

    @Test
    void newlinesPreservedButJsonEscapedNotRaw() {
        // A newline in the reason is meaningful (crash stack lines) and round-trips through JSON safely.
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.CRASH, "line1\nline2", List.of()));
        assertEquals("line1\nline2", embed.get("description").getAsString());
    }

    @Test
    void moduleListCapsAtThirtyWithMoreTail() {
        List<String> mods = new ArrayList<>();
        for (int i = 0; i < 42; i++) mods.add("Mod" + i + " (COMBAT)");
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.KICK, "r", mods));
        String modsField = fieldValue(embed, "Enabled modules (42)");
        assertTrue(modsField.contains("…and 12 more"), "should show 30 then '…and 12 more'");
        assertTrue(modsField.contains("Mod0 (COMBAT)"));
        assertFalse(modsField.contains("Mod41"), "capped entries must not appear");
    }

    @Test
    void emptyModuleListShowsNonePlaceholder() {
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.KICK, "r", List.of()));
        assertEquals("*(none enabled)*", fieldValue(embed, "Enabled modules (0)"));
    }

    @Test
    void longReasonTruncatedToLimit() {
        String huge = "x".repeat(5000);
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.CRASH, huge, List.of()));
        assertTrue(embed.get("description").getAsString().length() <= 4096);
    }

    @Test
    void nullReasonHandled() {
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.CRASH, null, null));
        assertEquals("(no reason given)", embed.get("description").getAsString());
    }

    @Test
    void noServerFieldIsEverEmitted() {
        // Server name/IP is not collected at all — the only fields are Event and the module list.
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.KICK, "kicked", List.of("KillAura (COMBAT)")));
        JsonArray fields = embed.getAsJsonArray("fields");
        assertEquals(2, fields.size());
        for (int i = 0; i < fields.size(); i++) {
            assertFalse(fields.get(i).getAsJsonObject().get("name").getAsString().equalsIgnoreCase("Server"),
                "no Server field may be present");
        }
        assertEquals("Event", fields.get(0).getAsJsonObject().get("name").getAsString());
    }

    // --- direct helper coverage (same-package access) ---

    @Test
    void cleanStripsControlKeepsPrintableAndTab() {
        assertEquals("ok", FlagPayload.clean("ok"));
        assertEquals("tab\there", FlagPayload.clean("tab\there")); // \t (0x09) kept
    }

    @Test
    void truncateAddsEllipsis() {
        assertEquals("abcd", FlagPayload.truncate("abcd", 10));
        assertEquals("ab…", FlagPayload.truncate("abcdef", 3));
    }

    private static String fieldValue(JsonObject embed, String name) {
        JsonArray fields = embed.getAsJsonArray("fields");
        for (int i = 0; i < fields.size(); i++) {
            JsonObject f = fields.get(i).getAsJsonObject();
            if (f.get("name").getAsString().equals(name)) return f.get("value").getAsString();
        }
        throw new AssertionError("no field named " + name);
    }
}
