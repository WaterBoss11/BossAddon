package com.boss.utility.flag;

import java.util.regex.Pattern;

/**
 * Best-effort redaction of privacy-sensitive tokens from a client log line before it goes into a flag report.
 * The flag system deliberately never sends server name/IP; log lines can leak the same info through the back
 * door, so every line passes through here first. <b>Verbatim copy of boss-pvp's LogSanitizer — the regex logic is unit-tested there; keep the two in sync.</b>
 *
 * <p><b>Honest about the limits:</b> log text is freeform, so this cannot guarantee 100% removal. It strips
 * what is reliably pattern-matchable — IPv4/IPv6 addresses, common-TLD domains, and the Windows username in
 * {@code C:\Users\<name>\} paths — and applies a heuristic for vanilla {@code <name>} chat. Server-custom
 * chat formats and other players' names in freeform text are NOT reliably detectable and may survive; the
 * design errs toward over-stripping, but callers should treat the excerpt as "scrubbed, not guaranteed
 * clean". Pure/static so it is unit-testable in isolation.
 */
public final class LogSanitizer {

    private LogSanitizer() {}

    // IPv4, optional :port — "192.168.0.1", "1.2.3.4:25565". (A 3-part version like "1.20.2" won't match.)
    private static final Pattern IPV4 =
        Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?\\b");

    // IPv6 candidate: 2-7 colon-separated hex groups. Only redacted when it actually looks like IPv6 (has a
    // "::" or 7 colons) so plain "HH:MM:SS" log timestamps are left intact.
    private static final Pattern IPV6 =
        Pattern.compile("(?i)\\b(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}\\b");

    // C:\Users\<name>\  /  c:/users/<name>/  — keep the drive + "Users", redact the username segment.
    private static final Pattern WIN_USER =
        Pattern.compile("(?i)([a-z]:[\\\\/]users[\\\\/])[^\\\\/\\r\\n]+");

    // hostname ending in a common TLD ("play.hypixel.net", "mc.example.com"). TLD-anchored so Java package /
    // class names ("net.minecraft.client.Minecraft") and dotted filenames ("boss-pvp.mixins.json") are left.
    private static final Pattern DOMAIN = Pattern.compile(
        "(?i)\\b(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+"
        + "(?:com|net|org|io|gg|me|tv|co|dev|xyz|info|online|club|world|pro|host|fun|us|eu|uk|ca|au|ru|de|mc)\\b");

    // Vanilla chat "<Name>" — redact the name (valid MC username chars), but never the "<init>"/"<clinit>"
    // markers that show up in stack traces.
    private static final Pattern CHAT = Pattern.compile("<([A-Za-z0-9_]{1,16})>");

    // Vanilla connection log "username[/1.2.3.4:port]" — redact the name before the bracketed address.
    private static final Pattern CONN_NAME = Pattern.compile("\\b[A-Za-z0-9_]{1,16}\\[/");

    // "Connecting to <server>, <port>" — redact the connect target (catches hosts with any/no TLD).
    private static final Pattern CONNECTING = Pattern.compile("(?i)(Connecting to\\s+)[^\\s,]+");

    // Vanilla "<name> joined the game" / "<name> left the game" — a bare username in a non-chat line.
    private static final Pattern JOIN_LEAVE = Pattern.compile("\\b[A-Za-z0-9_]{1,16} (joined|left) the game\\b");

    // "Setting user: <name>" — the local account username logged in a non-chat line.
    private static final Pattern SETTING_USER = Pattern.compile("(?i)(Setting user:\\s*)\\S+");

    // "<name> lost connection: ..." — vanilla disconnect log.
    private static final Pattern LOST_CONN = Pattern.compile("\\b[A-Za-z0-9_]{1,16} (lost connection)");

    // "<name> has made/reached/completed the advancement/goal/challenge [...]" — vanilla advancement log.
    private static final Pattern ADVANCEMENT = Pattern.compile("\\b[A-Za-z0-9_]{1,16} (has (?:made|reached|completed) the)");

    /** Redact using {@link #sanitize(String, String)} without a known local username. */
    public static String sanitize(String line) {
        return sanitize(line, null);
    }

    /**
     * Redact IPs, common-TLD domains, connect targets, the Windows username in paths, and player names in the
     * vanilla chat / join-leave / connection / disconnect / advancement / "Setting user" formats. When the
     * reporting client's own {@code localUsername} is known, every remaining exact occurrence of it is redacted
     * too (it is already sent as the report's Player field, so it should not be spread through the excerpt) —
     * this also covers self-hosted command output like "/give … to &lt;name&gt;" or "Teleported &lt;name&gt;".
     *
     * <p>Not covered (documented limitations): OTHER players' names in server-custom chat formats or in a
     * self-hosted world's command output are freeform and are NOT reliably distinguishable from ordinary log
     * text, so they may survive. We deliberately do not add blanket "to X"/"by X" rules — they would shred
     * legitimate log content — and document the gap instead.
     */
    public static String sanitize(String line, String localUsername) {
        if (line == null) return "";
        String s = line;

        // Redact the "Connecting to <target>" address wholesale FIRST — before the IP/domain rules touch it —
        // so an IP target becomes one clean "[host removed]" instead of a mangled "[ip removed] removed]".
        s = CONNECTING.matcher(s).replaceAll("$1[host removed]");
        s = IPV6.matcher(s).replaceAll(mr -> {
            String m = mr.group();
            long colons = m.chars().filter(c -> c == ':').count();
            return (m.contains("::") || colons >= 7) ? "[ip removed]" : java.util.regex.Matcher.quoteReplacement(m);
        });
        s = IPV4.matcher(s).replaceAll("[ip removed]");
        s = CONN_NAME.matcher(s).replaceAll("[player][/");
        s = JOIN_LEAVE.matcher(s).replaceAll("[player] $1 the game");
        s = LOST_CONN.matcher(s).replaceAll("[player] $1");
        s = ADVANCEMENT.matcher(s).replaceAll("[player] $1");
        s = SETTING_USER.matcher(s).replaceAll("$1[player]");
        s = WIN_USER.matcher(s).replaceAll("$1[user]");
        s = DOMAIN.matcher(s).replaceAll("[host removed]");
        s = CHAT.matcher(s).replaceAll(mr -> {
            String n = mr.group(1);
            return (n.equals("init") || n.equals("clinit")) ? java.util.regex.Matcher.quoteReplacement(mr.group()) : "<[player]>";
        });
        if (localUsername != null && localUsername.length() >= 3) {
            try {
                s = s.replaceAll("(?i)\\b" + Pattern.quote(localUsername) + "\\b", "[player]");
            } catch (Throwable ignored) {
                // a pathological username shouldn't break sanitization
            }
        }
        return s;
    }
}
