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
 * <p>Injection safety mirrors {@code scripts/discord-notify.mjs}: every untrusted string (disconnect reason,
 * module names, server name) is put into a Gson object graph and serialized by Gson — never concatenated
 * into hand-built JSON or a shell command. On top of that we strip control characters, neutralize
 * {@code @everyone}/{@code @here} with a zero-width space, and truncate to Discord's documented limits.
 */
public final class FlagPayload {

    private FlagPayload() {}

    private static final Gson GSON = new Gson();

    // Discord field/description/title length limits.
    private static final int LIM_TITLE = 256, LIM_DESC = 4096, LIM_FIELD = 1024;
    private static final int MODULE_LIST_CAP = 30;

    /** Report category: display label + a distinct embed colour (orange kicks, harsher red crash). */
    public enum Type {
        PACKET_KICK("Packet kick", 0xD35400),   // dark orange
        KICK("Kick", 0xE67E22),                 // orange
        CRASH("Crash", 0xC0392B);               // harsh red (distinct from commit/star 0xE74C3C)

        public final String label;
        public final int color;

        Type(String label, int color) {
            this.label = label;
            this.color = color;
        }
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
     * Build the full webhook payload JSON string. {@code reason} may be null; {@code modules} may be empty.
     * {@code isoTimestamp} should be an ISO-8601 string (Discord renders it locally).
     *
     * <p>By design this reports only the event type, reason, and enabled modules — <b>no server name or IP
     * is accepted or emitted</b> (there is no server parameter). Keep it that way.
     */
    public static String build(Type type, String reason, List<String> modules,
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
}
