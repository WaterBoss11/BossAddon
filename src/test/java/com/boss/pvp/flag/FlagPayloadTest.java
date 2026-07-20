package com.boss.pvp.flag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure flag-payload builder: valid JSON out, per-type colour, and — the important part —
 * injection safety (control chars stripped, mass mentions neutralized, everything serialized by Gson rather
 * than concatenated), plus module-list capping and field truncation. Also covers the disconnect classifier
 * and the multipart body used to ship the log as a .txt attachment.
 */
class FlagPayloadTest {

    private static final String LOGO = "https://example/logo.png";
    private static final String REPO = "WaterBoss11/BossAddon";
    private static final String ZWSP = "​";

    // Most tests don't care about the username; inject a fixed one. The username-specific tests call
    // buildParsedUser directly.
    private static JsonObject buildParsed(FlagPayload.Type type, String reason, List<String> modules) {
        return buildParsedUser(type, reason, "TestPlayer", modules);
    }

    private static JsonObject buildParsedUser(FlagPayload.Type type, String reason, String username, List<String> modules) {
        String json = FlagPayload.build(type, reason, username, modules, "2026-07-17T00:00:00Z", LOGO, REPO);
        return JsonParser.parseString(json).getAsJsonObject(); // throws if not valid JSON
    }

    private static JsonObject embedOf(JsonObject payload) {
        return payload.getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    @Test
    void producesValidJsonWithUsernameAndEmbed() {
        JsonObject p = buildParsed(FlagPayload.Type.KICKED, "You were kicked", List.of("KillAura (COMBAT)"));
        assertEquals("Boss PVP Flags", p.get("username").getAsString());
        assertEquals(1, p.getAsJsonArray("embeds").size());
    }

    @Test
    void colorMatchesType() {
        assertEquals(0xD35400, embedOf(buildParsed(FlagPayload.Type.SERVER_REJECTED, "r", List.of())).get("color").getAsInt());
        assertEquals(0xE67E22, embedOf(buildParsed(FlagPayload.Type.KICKED, "r", List.of())).get("color").getAsInt());
        assertEquals(0x7F8C8D, embedOf(buildParsed(FlagPayload.Type.TIMED_OUT, "r", List.of())).get("color").getAsInt());
        assertEquals(0x95A5A6, embedOf(buildParsed(FlagPayload.Type.DISCONNECTED, "r", List.of())).get("color").getAsInt());
        assertEquals(0xC0392B, embedOf(buildParsed(FlagPayload.Type.CRASH, "r", List.of())).get("color").getAsInt());
    }

    @Test
    void titleAndEventUseTheClearLabel() {
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.SERVER_REJECTED, "vpn", List.of()));
        assertEquals("⚠ Server rejected connection", embed.get("title").getAsString());
        assertEquals("Server rejected connection", fieldValue(embed, "Event"));
    }

    @Test
    void controlCharsStrippedFromReason() {
        // bell (0x07) and a raw ESC (0x1B) inside the reason must be removed; printable text survives.
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.KICKED, "badreason", List.of()));
        assertEquals("badreason", embed.get("description").getAsString());
    }

    @Test
    void massMentionsNeutralized() {
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.KICKED, "ping @everyone and @here", List.of()));
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
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.KICKED, "r", mods));
        String modsField = fieldValue(embed, "Enabled modules (42)");
        assertTrue(modsField.contains("…and 12 more"), "should show 30 then '…and 12 more'");
        assertTrue(modsField.contains("Mod0 (COMBAT)"));
        assertFalse(modsField.contains("Mod41"), "capped entries must not appear");
    }

    @Test
    void emptyModuleListShowsNonePlaceholder() {
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.KICKED, "r", List.of()));
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
    void embedHasExactlyThreeFieldsAndNoLogOrServerField() {
        // Server name/IP is not collected, and the log now ships as an attachment — so the embed carries
        // exactly Event, Player, and the module list. Never a Server field, never a Recent-log field.
        JsonObject embed = embedOf(buildParsed(FlagPayload.Type.KICKED, "kicked", List.of("KillAura (COMBAT)")));
        JsonArray fields = embed.getAsJsonArray("fields");
        assertEquals(3, fields.size());
        for (int i = 0; i < fields.size(); i++) {
            String name = fields.get(i).getAsJsonObject().get("name").getAsString();
            assertFalse(name.equalsIgnoreCase("Server"), "no Server field may be present");
            assertFalse(name.startsWith("Recent log"), "the log is an attachment, not an embed field");
        }
        assertEquals("Event", fields.get(0).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void usernameAppearsAsPlayerField() {
        JsonObject embed = embedOf(buildParsedUser(FlagPayload.Type.KICKED, "kicked", "Notch", List.of()));
        assertEquals("Notch", fieldValue(embed, "Player"));
    }

    @Test
    void usernameIsSanitized() {
        // A username can't be trusted any more than a reason: mass mentions neutralized, control chars gone.
        JsonObject embed = embedOf(buildParsedUser(FlagPayload.Type.KICKED, "r", "ev@everyoneil", List.of()));
        String player = fieldValue(embed, "Player");
        assertFalse(player.contains("@everyone"), "raw @everyone must not survive in the username");
        assertTrue(player.contains("@" + ZWSP + "everyone"));
    }

    @Test
    void nullOrBlankUsernameShowsUnknown() {
        assertEquals("unknown", fieldValue(embedOf(buildParsedUser(FlagPayload.Type.CRASH, "r", null, List.of())), "Player"));
        assertEquals("unknown", fieldValue(embedOf(buildParsedUser(FlagPayload.Type.CRASH, "r", "  ", List.of())), "Player"));
    }

    // --- disconnect classification (Part 1) ---

    @Test
    void vpnProxyBlockIsServerRejected() {
        // Real example 1: a VPN/proxy plugin refuses the connection during config — server sent a reason,
        // we were never in-world.
        assertEquals(FlagPayload.Type.SERVER_REJECTED,
            FlagPayload.classifyDisconnect(true, false, "Proxy/VPN detected. Disable it to join."));
    }

    @Test
    void plainTimeoutIsTimedOut() {
        // Real example 2: a plain timeout — no server disconnect packet, vanilla "Timed out" reason.
        assertEquals(FlagPayload.Type.TIMED_OUT,
            FlagPayload.classifyDisconnect(false, false, "Timed out"));
        assertEquals(FlagPayload.Type.TIMED_OUT,
            FlagPayload.classifyDisconnect(false, true, "Read timed out"));
    }

    @Test
    void wrongLoaderRefusalIsServerRejected() {
        // Real example 3: a server that requires a different loader refuses during connect.
        assertEquals(FlagPayload.Type.SERVER_REJECTED,
            FlagPayload.classifyDisconnect(true, false, "This server requires NeoForge to join."));
    }

    @Test
    void midGameServerKickIsKicked() {
        assertEquals(FlagPayload.Type.KICKED,
            FlagPayload.classifyDisconnect(true, true, "Kicked by an operator."));
    }

    @Test
    void loginPhaseRefusalWithoutPacketIsServerRejected() {
        // A login-phase refusal (e.g. banned) with no common Disconnect packet: no packet, never in-world.
        assertEquals(FlagPayload.Type.SERVER_REJECTED,
            FlagPayload.classifyDisconnect(false, false, "You are banned from this server"));
    }

    @Test
    void midGameConnectionLossIsDisconnected() {
        assertEquals(FlagPayload.Type.DISCONNECTED,
            FlagPayload.classifyDisconnect(false, true, "Connection Lost"));
    }

    @Test
    void looksLikeTimeoutMatchesCommonPhrasings() {
        assertTrue(FlagPayload.looksLikeTimeout("Timed out"));
        assertTrue(FlagPayload.looksLikeTimeout("connection reset by peer"));
        assertTrue(FlagPayload.looksLikeTimeout("No keepalive received"));
        assertFalse(FlagPayload.looksLikeTimeout("You are banned"));
        assertFalse(FlagPayload.looksLikeTimeout(null));
    }

    // --- multipart / attachment (Part 2) ---

    @Test
    void multipartBodyContainsPayloadJsonAndFilePart() {
        String json = FlagPayload.build(FlagPayload.Type.KICKED, "kicked", "u", List.of(), "2026-07-17T00:00:00Z", LOGO, REPO);
        byte[] body = FlagPayload.multipartBody(json, "INFO [net] connect [ip removed]\nWARN retry", "flag-log-2026.txt");
        String s = new String(body, StandardCharsets.UTF_8);
        assertTrue(s.contains("name=\"payload_json\""), "must carry the embed JSON as payload_json");
        assertTrue(s.contains("name=\"files[0]\"; filename=\"flag-log-2026.txt\""), "must carry the log as files[0]");
        assertTrue(s.contains("[ip removed]"), "the sanitized log text is present in the file part");
        assertTrue(s.contains("--" + FlagPayload.BOUNDARY + "--"), "must be a well-formed multipart body");
    }

    @Test
    void multipartBodyOmitsFilePartWhenNoLog() {
        byte[] body = FlagPayload.multipartBody("{}", null, "x.txt");
        String s = new String(body, StandardCharsets.UTF_8);
        assertTrue(s.contains("name=\"payload_json\""));
        assertFalse(s.contains("files[0]"), "no file part when there is no log");
    }

    @Test
    void filenameIsSanitizedToSafeChars() {
        assertEquals("flag-log.txt", FlagPayload.sanitizeFilename(null));
        assertEquals("flag-log.txt", FlagPayload.sanitizeFilename("  "));
        assertEquals("a_b_c.txt", FlagPayload.sanitizeFilename("a b\"c.txt"));
        assertEquals("flag-log-2026-07-18.txt", FlagPayload.sanitizeFilename("flag-log-2026-07-18.txt"));
    }

    // --- direct helper coverage (same-package access) ---

    @Test
    void cleanStripsControlKeepsPrintableAndTab() {
        assertEquals("ok", FlagPayload.clean("ok"));
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
