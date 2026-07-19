package com.boss.pvp.flag;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

/**
 * Pure builder for the Discord "flags" webhook payload (kick / packet-kick / crash reports). No Minecraft
 * types here so it is unit-testable in isolation — {@link FlagReporter} extracts plain strings from the
 * live client and hands them in.
 *
 * <p>Reports the event type, reason, the reporting client's own Minecraft username, and the enabled
 * modules — <b>never a server name or IP</b> (neither is accepted as a parameter).
 *
 * <p>Injection safety mirrors {@code scripts/discord-notify.mjs}: every untrusted string (disconnect reason,
 * username, module names) is put into a Gson object graph and serialized by Gson — never concatenated
 * into hand-built JSON or a shell command. On top of that we strip control characters, neutralize
 * {@code @everyone}/{@code @here} with a zero-width space, and truncate to Discord's documented limits.
 */
public final class FlagPayload {

    private FlagPayload() {}

    private static final Gson GSON = new Gson();

    // Discord field/description/title length limits.
    private static final int LIM_TITLE = 256, LIM_DESC = 4096, LIM_FIELD = 1024;
    private static final int MODULE_LIST_CAP = 30;

    // Fixed multipart boundary for the flag webhook file upload. A constant is fine: the body content is
    // controlled/escaped and never contains this token, so there is no collision risk (no randomness needed).
    public static final String BOUNDARY = "----BossPvpFlagBoundary7MA4YWxkTrZu0gW";

    /**
     * Report category: a clear display label + a distinct embed colour.
     *
     * <p>The disconnect side used to be a vague {@code PACKET_KICK}/{@code KICK} split that lumped a VPN/proxy
     * block, a wrong-loader refusal, and a real timeout into confusing buckets. It is now split by what
     * actually happened (see {@link #classifyDisconnect}): the server refused the connection, the server
     * kicked us mid-game, the connection timed out, or the connection dropped.
     */
    public enum Type {
        SERVER_REJECTED("Server rejected connection", 0xD35400),   // dark orange — VPN/proxy, wrong loader, whitelist, ban
        KICKED("Kicked", 0xE67E22),                                // orange — server kicked us while in-world (anticheat/admin)
        TIMED_OUT("Timed out", 0x7F8C8D),                          // slate — connection timed out / reset
        DISCONNECTED("Disconnected", 0x95A5A6),                    // grey — connection lost mid-game, no reason
        CRASH("Crash", 0xC0392B);                                  // harsh red (distinct from commit/star 0xE74C3C)

        public final String label;
        public final int color;

        Type(String label, int color) {
            this.label = label;
            this.color = color;
        }
    }

    /**
     * Classify a disconnect into a clear category from three signals — pure so it is unit-testable against real
     * disconnect messages:
     * <ul>
     *   <li>{@code serverSentReason} — the server actively sent a Disconnect packet (vs the connection just
     *       dropping on its own).</li>
     *   <li>{@code inWorld} — we had already reached the in-world (play) phase, vs still logging in/configuring.</li>
     *   <li>{@code reason} — the disconnect reason text (for the timeout heuristic).</li>
     * </ul>
     *
     * <p>Mapping:
     * <ul>
     *   <li>server sent a reason, not yet in-world → {@link Type#SERVER_REJECTED} (VPN/proxy block, wrong
     *       loader, whitelist, ban, "server full").</li>
     *   <li>server sent a reason, already in-world → {@link Type#KICKED} (anticheat / admin kick mid-game).</li>
     *   <li>no packet, reason looks like a timeout → {@link Type#TIMED_OUT}.</li>
     *   <li>no packet, never reached the world → {@link Type#SERVER_REJECTED} (login-phase refusal without a
     *       common Disconnect packet).</li>
     *   <li>no packet, in-world → {@link Type#DISCONNECTED} (connection lost mid-game).</li>
     * </ul>
     */
    public static Type classifyDisconnect(boolean serverSentReason, boolean inWorld, String reason) {
        if (serverSentReason) return inWorld ? Type.KICKED : Type.SERVER_REJECTED;
        if (looksLikeTimeout(reason)) return Type.TIMED_OUT;
        return inWorld ? Type.DISCONNECTED : Type.SERVER_REJECTED;
    }

    /**
     * Heuristic: does this reason read like a network timeout / involuntary drop? Best-effort and English-biased
     * (vanilla resolves {@code disconnect.timeout} to the client locale) — a miss only falls back to the
     * {@code DISCONNECTED}/{@code SERVER_REJECTED} label, never to a wrong "kick". Package-private for testing.
     */
    static boolean looksLikeTimeout(String reason) {
        if (reason == null) return false;
        String r = reason.toLowerCase(java.util.Locale.ROOT);
        return r.contains("timed out") || r.contains("timeout") || r.contains("read timed out")
            || r.contains("connection reset") || r.contains("keepalive") || r.contains("keep-alive")
            || r.contains("keep alive");
    }

    /** Strip control chars (keep \n, \t), neutralize mass mentions. Gson does the JSON escaping. */
    static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")
                .replaceAll("@(everyone|here)", "@​$1");
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Bulleted module list, capped with an "…and N more" tail so it never overruns the field limit. */
    static String modulesBlock(List<String> modules) {
        if (modules == null || modules.isEmpty()) return "*(none enabled)*";
        StringBuilder sb = new StringBuilder();
        int shown = Math.min(modules.size(), MODULE_LIST_CAP);
        for (int i = 0; i < shown; i++) {
            if (i > 0) sb.append('\n');
            sb.append("• ").append(clean(modules.get(i)));
        }
        int more = modules.size() - shown;
        if (more > 0) sb.append("\n…and ").append(more).append(" more");
        return truncate(sb.toString(), LIM_FIELD);
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", truncate(clean(name), LIM_TITLE));
        String v = truncate(clean(value), LIM_FIELD);
        f.addProperty("value", v.isEmpty() ? "—" : v);
        f.addProperty("inline", inline);
        return f;
    }

    /**
     * Build the {@code multipart/form-data} body for a flag report: the embed JSON as the {@code payload_json}
     * part, plus — when a sanitized log excerpt is present — a {@code files[0]} text attachment named
     * {@code filename}. Pure/testable; bytes are UTF-8. The log is delivered as a downloadable {@code .txt}
     * file instead of a truncated embed field, so the full sanitized excerpt survives.
     *
     * <p>The {@code logText} handed in here is ALREADY sanitized by {@link LogSanitizer} upstream — this method
     * neither relaxes nor re-does that; it only frames it as a file part.
     */
    public static byte[] multipartBody(String payloadJson, String logText, String filename) {
        StringBuilder sb = new StringBuilder();
        sb.append("--").append(BOUNDARY).append("\r\n");
        sb.append("Content-Disposition: form-data; name=\"payload_json\"\r\n");
        sb.append("Content-Type: application/json\r\n\r\n");
        sb.append(payloadJson == null ? "{}" : payloadJson).append("\r\n");
        if (logText != null && !logText.isBlank()) {
            sb.append("--").append(BOUNDARY).append("\r\n");
            sb.append("Content-Disposition: form-data; name=\"files[0]\"; filename=\"")
              .append(sanitizeFilename(filename)).append("\"\r\n");
            sb.append("Content-Type: text/plain; charset=utf-8\r\n\r\n");
            sb.append(logText).append("\r\n");
        }
        sb.append("--").append(BOUNDARY).append("--\r\n");
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Keep a filename to safe chars (defends the Content-Disposition header); fall back to a fixed name. */
    static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "flag-log.txt";
        String s = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isBlank() ? "flag-log.txt" : s;
    }

    /**
     * Build the full webhook payload JSON string. {@code reason}/{@code username} may be null; {@code modules}
     * may be empty. {@code isoTimestamp} should be an ISO-8601 string (Discord renders it locally).
     *
     * <p>The recent-log excerpt is NOT part of this embed — it ships as a separate {@code .txt} attachment via
     * {@link #multipartBody}. {@code username} is the reporting client's own Minecraft name. There is
     * deliberately no server parameter — <b>server name/IP is never accepted or emitted</b>. Keep it that way.
     */
    public static String build(Type type, String reason, String username, List<String> modules,
                               String isoTimestamp, String logoUrl, String repo) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", truncate("⚠ " + type.label, LIM_TITLE));
        embed.addProperty("color", type.color);

        String desc = clean(reason == null || reason.isBlank() ? "(no reason given)" : reason);
        embed.addProperty("description", truncate(desc, LIM_DESC));

        if (logoUrl != null && !logoUrl.isBlank()) {
            JsonObject thumb = new JsonObject();
            thumb.addProperty("url", logoUrl);
            embed.add("thumbnail", thumb);
        }

        JsonArray fields = new JsonArray();
        fields.add(field("Event", type.label, true));
        fields.add(field("Player", username == null || username.isBlank() ? "unknown" : username, true));
        fields.add(field("Enabled modules (" + (modules == null ? 0 : modules.size()) + ")",
                modulesBlock(modules), false));
        embed.add("fields", fields);

        if (repo != null && !repo.isBlank()) {
            JsonObject footer = new JsonObject();
            footer.addProperty("text", clean(repo));
            embed.add("footer", footer);
        }
        if (isoTimestamp != null && !isoTimestamp.isBlank()) {
            embed.addProperty("timestamp", isoTimestamp);
        }

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject payload = new JsonObject();
        payload.addProperty("username", "Boss PVP Flags");
        if (logoUrl != null && !logoUrl.isBlank()) payload.addProperty("avatar_url", logoUrl);
        payload.add("embeds", embeds);

        return GSON.toJson(payload);
    }

    /**
     * Build the COMBINED payload used when both boss-pvp and BossUtility are installed: one embed listing
     * each addon's enabled modules as its own field. {@code utilModules == null} means the BossUtility bridge
     * couldn't be reached — the field then reads "(unavailable)" so a single report still fires. Same
     * event/player/reason/timestamp styling as {@link #build}; server name/IP is still never emitted.
     */
    public static String buildCombined(Type type, String reason, String username, List<String> pvpModules,
                                       List<String> utilModules, String isoTimestamp,
                                       String logoUrl, String repo) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", truncate("⚠ " + type.label, LIM_TITLE));
        embed.addProperty("color", type.color);

        String desc = clean(reason == null || reason.isBlank() ? "(no reason given)" : reason);
        embed.addProperty("description", truncate(desc, LIM_DESC));

        if (logoUrl != null && !logoUrl.isBlank()) {
            JsonObject thumb = new JsonObject();
            thumb.addProperty("url", logoUrl);
            embed.add("thumbnail", thumb);
        }

        JsonArray fields = new JsonArray();
        fields.add(field("Event", type.label, true));
        fields.add(field("Player", username == null || username.isBlank() ? "unknown" : username, true));
        fields.add(field("boss-pvp modules (" + (pvpModules == null ? 0 : pvpModules.size()) + ")",
                modulesBlock(pvpModules), false));
        fields.add(field("BossUtility modules (" + (utilModules == null ? "?" : utilModules.size()) + ")",
                utilModules == null ? "*(unavailable)*" : modulesBlock(utilModules), false));
        embed.add("fields", fields);

        if (repo != null && !repo.isBlank()) {
            JsonObject footer = new JsonObject();
            footer.addProperty("text", clean(repo));
            embed.add("footer", footer);
        }
        if (isoTimestamp != null && !isoTimestamp.isBlank()) {
            embed.addProperty("timestamp", isoTimestamp);
        }

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject payload = new JsonObject();
        payload.addProperty("username", "Boss Utility+PVP Flags");
        if (logoUrl != null && !logoUrl.isBlank()) payload.addProperty("avatar_url", logoUrl);
        payload.add("embeds", embeds);

        return GSON.toJson(payload);
    }
}
