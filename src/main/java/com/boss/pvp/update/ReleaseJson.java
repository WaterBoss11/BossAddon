package com.boss.pvp.update;

/**
 * Minimal, dependency-free extraction of the {@code tag_name} field from a GitHub "latest release" JSON
 * response. Kept tiny and pure (no JSON library, no Minecraft deps) so it's trivially unit-testable and can't
 * drag a parser onto the launch path. We only need one string field, so a targeted scan beats a full parse.
 */
public final class ReleaseJson {

    private ReleaseJson() {}

    /**
     * Return the value of the first {@code "tag_name": "..."} pair, or null if absent/malformed. Handles
     * arbitrary whitespace around the colon and simple backslash escapes inside the value.
     */
    public static String extractTagName(String json) {
        if (json == null) return null;
        int key = json.indexOf("\"tag_name\"");
        if (key < 0) return null;
        int colon = json.indexOf(':', key + "\"tag_name\"".length());
        if (colon < 0) return null;

        int i = colon + 1;
        while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
        if (i >= json.length() || json.charAt(i) != '"') return null;   // e.g. "tag_name": null
        i++;   // past the opening quote

        StringBuilder sb = new StringBuilder();
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {   // keep the escaped char verbatim (tags are plain ASCII)
                sb.append(json.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
            i++;
        }
        String tag = sb.toString().trim();
        return tag.isEmpty() ? null : tag;
    }
}
