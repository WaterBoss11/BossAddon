package com.boss.pvp.relay;

/**
 * Pure, unit-testable construction of the legacy §-code strings BossChat renders into the vanilla chat log.
 * This is the single home of the feature's visual language, so every message type stays consistent — the goal
 * is for BossChat to read as a deliberately designed feature, not a debug print.
 *
 * <p>Design language:
 * <ul>
 *   <li><b>Recognition dot.</b> Every BossChat line — chat AND status — starts with a "●" coloured by
 *       scope/state, so the feature is spottable at a glance in a busy chat log (and it mirrors the dot on
 *       the chat-toggle button).</li>
 *   <li><b>Chat messages</b> use a bright, scope-coloured badge ({@code [BossChat]} / {@code [BossChat·server]}
 *       / {@code [BossChat·DM]}), a white sender name, a grey "{@code :}" separator and a white body. Scope is
 *       encoded by BOTH colour and label — aqua global, green server, light-purple DM — so it never relies on
 *       colour alone.</li>
 *   <li><b>System / status lines</b> use a muted, <i>italic</i> "{@code BossChat — …}" form with no bracket and
 *       no "{@code name:}", so a status notice can never be mistaken for a real message at a glance.</li>
 *   <li><b>Unverified</b> senders get an understated italic-grey "{@code (unverified)}" marker — intentional,
 *       not a red warning label.</li>
 *   <li><b>Boss</b> keeps the relay's server-side bold-black "{@code [Boss]}" prefix (untouched); the client
 *       adds only a subtle gold accent on the name so the owner reads as premium without shouting.</li>
 *   <li><b>DM</b> is visually distinct from global/server: its own light-purple badge plus an explicit
 *       "{@code →}" direction cue (sender → you / you → recipient).</li>
 * </ul>
 *
 * <p><b>Minecraft gotcha handled here:</b> a legacy colour code (§0–§f) RESETS bold/italic, so italic is
 * re-applied after every colour change via {@link #it(String)}. Only glyphs proven to render in the MC font
 * are used: {@code ● · → … —}.
 *
 * <p>Message BODIES are already §-stripped by {@link RelaySanitizer} before they reach here, so this styling
 * is authoritative and cannot be spoofed by user text. Server-side rank/mute logic is untouched — this class
 * is pure client-side presentation.
 */
public final class BossChatFormat {

    private BossChatFormat() {}

    // ---- palette (legacy § codes) — the addon's established chat colours ---------------------------------
    static final String GLOBAL = "§b";   // aqua   — global scope
    static final String SERVER = "§a";   // green  — server scope
    static final String DM     = "§d";   // purple — direct message
    static final String GREY   = "§7";   // separators, "you", light scaffolding
    static final String DGREY  = "§8";   // muted markers / status scaffolding
    static final String WHITE  = "§f";   // names + body
    static final String GOLD   = "§6";   // subtle Boss name accent (warm, brand-adjacent)
    static final String WARN   = "§e";   // connecting
    static final String OK     = "§a";   // connected
    static final String ERR    = "§c";   // rejected / error
    static final String RESET  = "§r";
    static final String ITAL   = "§o";

    // ---- glyphs proven to render in the MC font ---------------------------------------------------------
    static final String DOT   = "●";
    static final String MIDDOT = "·";
    static final String ARROW = "→";
    static final String DASH  = "—";
    static final String ELL   = "…";

    /**
     * The exact prefix the relay server prepends for the Boss (see BossRelay ranks.js {@code displayFrom}):
     * bold-black "[Boss] ". Detected so the client can accent the name without altering the prefix. If the
     * server's format ever drifts, {@link #sender} degrades safely to a plain white name.
     */
    static final String BOSS_PREFIX = "§0§l[Boss]§r ";

    /** A colour code with italic re-applied (a colour code alone would clear italic). */
    static String it(String color) { return color + ITAL; }

    private static String norm(String scope) { return scope == null ? "" : scope; }

    // ---- chat messages ----------------------------------------------------------------------------------

    /** An inbound message from another user, in the given scope. {@code from} may carry the server Boss prefix. */
    public static String inbound(String scope, String from, boolean fromVerified, String body) {
        String who = sender(from, fromVerified);
        return switch (norm(scope)) {
            case "server" -> SERVER + DOT + " " + SERVER + "[BossChat" + MIDDOT + "server] " + who + GREY + ": " + WHITE + body;
            case "dm"     -> DM + DOT + " " + DM + "[BossChat" + MIDDOT + "DM] " + who + " " + DGREY + ARROW + " " + GREY + "you" + GREY + ": " + WHITE + body;
            default       -> GLOBAL + DOT + " " + GLOBAL + "[BossChat] " + who + GREY + ": " + WHITE + body;
        };
    }

    /** The local echo of a message you just sent (you never receive your own back from the relay). */
    public static String outbound(String scope, String to, boolean meVerified, String body) {
        String me = self(meVerified);
        return switch (norm(scope)) {
            case "server" -> SERVER + DOT + " " + SERVER + "[BossChat" + MIDDOT + "server] " + me + GREY + ": " + WHITE + body;
            case "dm"     -> DM + DOT + " " + DM + "[BossChat" + MIDDOT + "DM] " + GREY + "you " + DGREY + ARROW + " " + WHITE + (to == null ? "?" : to) + GREY + ": " + WHITE + body;
            default       -> GLOBAL + DOT + " " + GLOBAL + "[BossChat] " + me + GREY + ": " + WHITE + body;
        };
    }

    /** Render a sender name with rank/verification markers. Never visually identical across trust levels. */
    static String sender(String from, boolean verified) {
        String f = from == null ? "" : from;
        if (!verified) {
            // Understated, deliberate marker — italic grey parenthetical, then the white name.
            return it(DGREY) + "(unverified)" + RESET + " " + WHITE + f;
        }
        if (f.startsWith(BOSS_PREFIX)) {
            // Keep the server's bold-black [Boss] prefix exactly; accent only the name, in gold.
            return BOSS_PREFIX + GOLD + f.substring(BOSS_PREFIX.length());
        }
        return WHITE + f;   // regular verified user (and any Boss-prefix drift degrades safely to this)
    }

    /** Render "you" for the local echo, marked if our own connection is unverified. */
    static String self(boolean verified) {
        return verified
            ? GREY + "you"
            : it(DGREY) + "(unverified)" + RESET + " " + GREY + "you";
    }

    // ---- system / status lines (muted italic; structurally distinct from chat) ---------------------------

    /**
     * The shared status form: a state-coloured dot, then a muted italic "BossChat — {body}". {@code body}
     * supplies its own colour(s) with italic re-applied per segment (use {@link #it}).
     */
    static String status(String dotColor, String body) {
        return dotColor + DOT + " " + it(DGREY) + "BossChat " + it(DGREY) + DASH + " " + body;
    }

    public static String connecting() {
        return status(WARN, it(GREY) + "connecting" + ELL);
    }

    public static String connectingUnverified() {
        return status(WARN, it(GREY) + "connecting as " + it(DGREY) + "(unverified)" + it(GREY) + ELL);
    }

    public static String connectedVerified() {
        return status(OK, it(OK) + "connected " + it(DGREY) + "(verified) " + it(GREY) + MIDDOT
            + " type in chat or " + it(WHITE) + "/bosschat");
    }

    public static String connectedUnverified() {
        return status(OK, it(OK) + "connected " + it(DGREY) + "as (unverified) " + it(GREY) + MIDDOT
            + " others see you marked");
    }

    public static String disconnected() {
        return status(GREY, it(GREY) + "disconnected");
    }

    public static String rejected(String reason) {
        return status(ERR, it(ERR) + "rejected" + it(DGREY) + ": " + it(GREY) + (reason == null ? "unknown" : reason));
    }

    /** Verified-only relay refused an offline/unverified attempt ({@code forced} = user forced offline mode). */
    public static String verifiedOnly(boolean forced) {
        return status(ERR, it(ERR) + (forced
            ? "this relay is verified-only " + it(DGREY) + DASH + it(GREY) + " offline accounts aren't allowed"
            : "Mojang auth failed " + it(DGREY) + DASH + it(GREY) + " this relay is verified-only"));
    }

    public static String notConnected() {
        return status(ERR, it(ERR) + "not connected");
    }

    /** A generic system notice relayed from the server (already §-sanitized by the caller). */
    public static String system(String text) {
        return status(GREY, it(GREY) + (text == null ? "" : text));
    }

    // ---- /bosschat command feedback (same status form, so the command feels part of the feature) --------

    /** Response to a scope change: green when a scope is active, grey when turned off. */
    public static String scopeChanged(String modeName, boolean off) {
        String c = off ? GREY : OK;
        return status(c, it(c) + "scope " + it(DGREY) + "= " + it(WHITE) + modeName + " " + it(GREY) + MIDDOT + " "
            + it(GREY) + (off ? "chat is normal" : "typed chat goes to BossChat"));
    }

    /** Response to a manual /bosschat reconnect. */
    public static String reconnecting() {
        return status(WARN, it(GREY) + "reconnecting" + ELL);
    }

    /** Response to /bosschat status: current connection state and active scope. */
    public static String statusReport(String connStatus, String scope) {
        return status(GREY, it(GREY) + "status " + it(DGREY) + "= " + it(WHITE) + (connStatus == null ? "?" : connStatus)
            + " " + it(GREY) + MIDDOT + " scope " + it(DGREY) + "= " + it(WHITE) + (scope == null ? "?" : scope));
    }

    /** Shown when the command runs on a non-pilot install (BossChat is inert). */
    public static String notEnabled() {
        return status(GREY, it(GREY) + "not enabled on this install " + it(DGREY) + "(closed pilot " + DASH
            + " no invite configured)");
    }
}
