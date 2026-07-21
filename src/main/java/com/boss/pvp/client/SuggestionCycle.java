package com.boss.pvp.client;

/**
 * Pure helpers for the "?" command Tab-completion cycle and the visual suggestion popup — no Minecraft/Fabric
 * deps, so the index/base/insertion math is unit-testable directly. The suggestion <em>content</em> (which
 * strings match a partial line) comes from {@link com.boss.pvp.BossAddonInit#suggest}; this only covers how the
 * cycle walks that list and what text a Tab insertion produces, which is the logic shared by the chat mixin (the
 * actual insertion) and {@link com.boss.pvp.client.gui.SuggestionPopupWidget} (the highlight).
 */
public final class SuggestionCycle {

    private SuggestionCycle() {}

    /**
     * The fixed part of the line a completion is appended to: everything up to and including the last space. A
     * completion replaces the final (partial) token, so {@code baseOf("pvp on") == "pvp "} and
     * {@code baseOf("pv") == ""}. Null-safe.
     */
    public static String baseOf(String withoutPrefix) {
        if (withoutPrefix == null) return "";
        int lastSpace = withoutPrefix.lastIndexOf(' ');
        return lastSpace < 0 ? "" : withoutPrefix.substring(0, lastSpace + 1);
    }

    /** Next index in a wrap-around Tab cycle. Returns 0 for an empty list so callers never index out of range. */
    public static int nextIndex(int current, int size) {
        if (size <= 0) return 0;
        return ((current + 1) % size + size) % size;
    }

    /**
     * Clamp a highlight index into {@code [0, size)} (0 for an empty list) so a stale index can't overflow the
     * drawn list. This bounds — it does not wrap like {@link #nextIndex}: below 0 gives 0, at/above the end gives
     * the last row. In normal use the index is always in range; this is pure defence for a list that shrank
     * between the Tab that set the index and the frame that draws it.
     */
    public static int clampIndex(int index, int size) {
        if (size <= 0) return 0;
        if (index < 0) return 0;
        if (index >= size) return size - 1;
        return index;
    }

    /**
     * The exact chat-input text a Tab insertion produces: the prefix, the fixed base of {@code withoutPrefix},
     * and the chosen completion (which is a full token, as returned by {@code suggest}). So
     * {@code applied("?", "pv", "pvp") == "?pvp"} and {@code applied("?", "pvp on", "off") == "?pvp off"}.
     */
    public static String applied(String prefix, String withoutPrefix, String completion) {
        return prefix + baseOf(withoutPrefix) + (completion == null ? "" : completion);
    }
}
