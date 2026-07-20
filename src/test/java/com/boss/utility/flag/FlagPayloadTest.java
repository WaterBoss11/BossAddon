package com.boss.utility.flag;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure flag-payload builder for BossUtility: valid JSON out, per-type colour, the disconnect
 * classifier (Part 1), and the multipart body that ships the log as a .txt attachment (Part 2).
 */
class FlagPayloadTest {

    private static final String LOGO = "https://example/logo.png";
    private static final String REPO = "WaterBoss11/BossUtility";

    private static JsonObject embedOf(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
    }

    private static String build(FlagPayload.Type type, String reason, List<String> modules) {
        return FlagPayload.build(type, reason, "TestPlayer", modules, "2026-07-17T00:00:00Z", LOGO, REPO);
    }

    @Test
    void producesValidJsonWithBotUsername() {
        JsonObject p = JsonParser.parseString(build(FlagPayload.Type.KICKED, "kicked", List.of())).getAsJsonObject();
        assertEquals("BossUtility Flags", p.get("username").getAsString());
        assertEquals(1, p.getAsJsonArray("embeds").size());
    }

    @Test
    void colorMatchesType() {
        assertEquals(0xD35400, embedOf(build(FlagPayload.Type.SERVER_REJECTED, "r", List.of())).get("color").getAsInt());
        assertEquals(0xE67E22, embedOf(build(FlagPayload.Type.KICKED, "r", List.of())).get("color").getAsInt());
        assertEquals(0x7F8C8D, embedOf(build(FlagPayload.Type.TIMED_OUT, "r", List.of())).get("color").getAsInt());
        assertEquals(0x95A5A6, embedOf(build(FlagPayload.Type.DISCONNECTED, "r", List.of())).get("color").getAsInt());
        assertEquals(0xC0392B, embedOf(build(FlagPayload.Type.CRASH, "r", List.of())).get("color").getAsInt());
    }

    @Test
    void embedHasThreeFieldsAndNoLogField() {
        JsonObject embed = embedOf(build(FlagPayload.Type.KICKED, "kicked", List.of("Sprint (MOVEMENT)")));
        JsonArray fields = embed.getAsJsonArray("fields");
        assertEquals(3, fields.size());
        for (int i = 0; i < fields.size(); i++) {
            assertFalse(fields.get(i).getAsJsonObject().get("name").getAsString().startsWith("Recent log"),
                "the log is an attachment, not an embed field");
        }
    }

    // --- disconnect classification (Part 1) ---

    @Test
    void vpnProxyBlockIsServerRejected() {
        assertEquals(FlagPayload.Type.SERVER_REJECTED,
            FlagPayload.classifyDisconnect(true, false, "Proxy/VPN detected. Disable it to join."));
    }

    @Test
    void plainTimeoutIsTimedOut() {
        assertEquals(FlagPayload.Type.TIMED_OUT, FlagPayload.classifyDisconnect(false, false, "Timed out"));
        assertEquals(FlagPayload.Type.TIMED_OUT, FlagPayload.classifyDisconnect(false, true, "Read timed out"));
    }

    @Test
    void wrongLoaderRefusalIsServerRejected() {
        assertEquals(FlagPayload.Type.SERVER_REJECTED,
            FlagPayload.classifyDisconnect(true, false, "This server requires NeoForge to join."));
    }

    @Test
    void midGameKickAndConnectionLoss() {
        assertEquals(FlagPayload.Type.KICKED, FlagPayload.classifyDisconnect(true, true, "Kicked by an operator."));
        assertEquals(FlagPayload.Type.DISCONNECTED, FlagPayload.classifyDisconnect(false, true, "Connection Lost"));
    }

    @Test
    void looksLikeTimeoutMatchesCommonPhrasings() {
        assertTrue(FlagPayload.looksLikeTimeout("Timed out"));
        assertTrue(FlagPayload.looksLikeTimeout("connection reset by peer"));
        assertFalse(FlagPayload.looksLikeTimeout("You are banned"));
        assertFalse(FlagPayload.looksLikeTimeout(null));
    }

    // --- multipart / attachment (Part 2) ---

    @Test
    void multipartBodyContainsPayloadJsonAndFilePart() {
        String json = build(FlagPayload.Type.KICKED, "kicked", List.of());
        byte[] body = FlagPayload.multipartBody(json, "INFO [net] connect [ip removed]\nWARN retry", "flag-log-2026.txt");
        String s = new String(body, StandardCharsets.UTF_8);
        assertTrue(s.contains("name=\"payload_json\""));
        assertTrue(s.contains("name=\"files[0]\"; filename=\"flag-log-2026.txt\""));
        assertTrue(s.contains("[ip removed]"));
        assertTrue(s.contains("--" + FlagPayload.BOUNDARY + "--"));
    }

    @Test
    void multipartBodyOmitsFilePartWhenNoLog() {
        String s = new String(FlagPayload.multipartBody("{}", null, "x.txt"), StandardCharsets.UTF_8);
        assertTrue(s.contains("name=\"payload_json\""));
        assertFalse(s.contains("files[0]"));
    }

    @Test
    void filenameIsSanitizedToSafeChars() {
        assertEquals("flag-log.txt", FlagPayload.sanitizeFilename(null));
        assertEquals("a_b_c.txt", FlagPayload.sanitizeFilename("a b\"c.txt"));
    }
}
