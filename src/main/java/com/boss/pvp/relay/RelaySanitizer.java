package com.boss.pvp.relay;

import java.util.regex.Pattern;

/**
 * Client-side scrubbing of outbound relay text — the SAME discipline the relay server applies, done here too
 * so the client never depends solely on server-side scrubbing (defense in depth, mirrors how the flag system
 * sanitizes before send rather than trusting the far end).
 *
 * <p>Strips control characters, removes Minecraft {@code §} formatting codes (so a message can't inject
 * styled/coloured text into another player's chat), neutralizes {@code @everyone}/{@code @here} (future
 * Discord-bridge safety), collapses to a single line, and caps length. Pure/static so it is unit-testable.
 */
public final class RelaySanitizer {

    private RelaySanitizer() {}

    /** Discord's field cap and the relay's max message length agree on this bound. */
    public static final int MAX_LEN = 512;

    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x1F\\x7F]");
    private static final Pattern SECTION = Pattern.compile("§.");        // § + next char (formatting code)
    private static final Pattern SECTION_LONE = Pattern.compile("§");    // any leftover lone §
    private static final Pattern MENTION = Pattern.compile("(?i)@(everyone|here)");

    public static String sanitize(String text) {
        if (text == null) return "";
        String s = text;
        s = CONTROL.matcher(s).replaceAll("");         // also removes newlines/tabs — relay text is single-line
        s = SECTION.matcher(s).replaceAll("");
        s = SECTION_LONE.matcher(s).replaceAll("");
        s = MENTION.matcher(s).replaceAll("@​$1");
        s = s.strip();
        if (s.length() > MAX_LEN) s = s.substring(0, MAX_LEN);
        return s;
    }

    /** True when the message is empty after sanitizing (nothing worth sending). */
    public static boolean isBlank(String text) {
        return sanitize(text).isEmpty();
    }
}
