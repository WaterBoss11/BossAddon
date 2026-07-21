package com.boss.pvp.relay;

import com.boss.pvp.BossPvpAddon;

/**
 * Resolves the chat-relay connection settings from the local client config ({@code boss-pvp.properties}, via
 * {@link BossPvpAddon#getConfigString}). Environment variables override the file so a tester can point at a
 * dev relay without editing config.
 *
 * <p><b>Public gate (hybrid access model).</b> BossChat is enabled whenever a relay URL is configured — the
 * public build bakes {@link #DEFAULT_URL}, so a normal install connects out of the box. Access is then decided
 * relay-side: any Mojang-<i>verified</i> account is let in with no invite, while <i>unverified</i> (cracked/
 * offline) identities stay gated — the relay enforces an allowlist/invite for them (they can supply one via
 * {@code relay.invite} below). The invite is never the security boundary; the relay is.
 *
 * <p>An install with no baked URL and no user-set URL is completely inert (no connection, no relay UI) — the
 * same inert behaviour older pilot builds had before a URL was configured.
 *
 * <p>Keys ({@code boss-pvp.properties}) / env vars:
 * <ul>
 *   <li>{@code relay.url} / {@code BOSS_PVP_RELAY_URL} — e.g. {@code wss://relay.example.com}</li>
 *   <li>{@code relay.invite} / {@code BOSS_PVP_RELAY_INVITE} — optional; only the gated unverified path needs it</li>
 * </ul>
 */
public final class RelayConfig {

    private RelayConfig() {}

    private static final String URL_KEY = "relay.url";
    private static final String INVITE_KEY = "relay.invite";
    private static final String OFFLINE_KEY = "relay.offline";
    private static final String PASSWORD_KEY = "relay.password";
    private static final String ENABLED_KEY = "relay.enabled";

    // Baked first-launch defaults, seeded into boss-pvp.properties on first run (never clobbering a manual edit).
    //
    // DEFAULT_URL is the PRODUCTION relay endpoint — baking it is what makes BossChat live for the public build.
    // >>> ESAD: confirm this is the production Render endpoint before release. It is the relay host from the
    //     pilot config; if production runs on a different Render service/URL, change this one string. <<<
    // DEFAULT_INVITE is EMPTY on purpose: under the hybrid model verified users need no invite. A gated
    // unverified tester who was given an invite sets it themselves via relay.invite (env/config), not here.
    static final String DEFAULT_URL = "wss://bossrelay.onrender.com";
    static final String DEFAULT_INVITE = "";
    private static final String URL_ENV = "BOSS_PVP_RELAY_URL";
    private static final String INVITE_ENV = "BOSS_PVP_RELAY_INVITE";
    private static final String OFFLINE_ENV = "BOSS_PVP_RELAY_OFFLINE";
    private static final String PASSWORD_ENV = "BOSS_PVP_RELAY_PASSWORD";
    private static final String ENABLED_ENV = "BOSS_PVP_RELAY_ENABLED";

    /** The relay WebSocket URL, or null if unset. Must be {@code ws://} or {@code wss://} to be usable. */
    public static String url() {
        String v = resolve(URL_ENV, URL_KEY);
        if (v == null) return null;
        v = v.trim();
        return (v.startsWith("ws://") || v.startsWith("wss://")) ? v : null;
    }

    /** The pilot invite code the user was given, or null if unset. */
    public static String invite() {
        String v = resolve(INVITE_ENV, INVITE_KEY);
        return v == null || v.isBlank() ? null : v.trim();
    }

    /**
     * The public gate: BossChat is active when the user hasn't opted out ({@link #enabled()}) AND a valid relay
     * URL is present. No invite is required here — verified users join without one, and the gated unverified
     * path enforces its own invite/allowlist relay-side. An install with no URL, or one the user disabled, stays
     * fully inert (no connection, no relay UI).
     */
    public static boolean isConfigured() {
        return enabled() && url() != null;
    }

    /**
     * User opt-out switch. Defaults to TRUE, so BossChat is on wherever a relay URL is configured. Setting it
     * false (via {@code ?bossaddon chat disable}, the {@code relay.enabled} key, or {@code BOSS_PVP_RELAY_ENABLED})
     * fully disables the feature: the client disconnects and never auto-connects, on this and future launches.
     */
    public static boolean enabled() {
        String v = resolve(ENABLED_ENV, ENABLED_KEY);
        if (v == null) return true;   // default on
        v = v.trim().toLowerCase();
        return !(v.equals("false") || v.equals("0") || v.equals("no") || v.equals("off"));
    }

    /** Persist the opt-out flag so it survives restarts. Called by the enable/disable command. */
    public static void setEnabled(boolean on) {
        try {
            BossPvpAddon.setConfigString(ENABLED_KEY, Boolean.toString(on));
        } catch (Throwable ignored) {
            // a config write must never break the command
        }
    }

    /**
     * Optional password used to CLAIM (and later resume) an unverified username, so a cracked/offline account
     * can keep the same name across sessions. Only sent on the offline auth path. Sending it on a free name
     * claims it; reconnecting to a claimed name requires the same password. Never used for verified accounts,
     * and never lets an unverified user take a verified name (enforced relay-side).
     */
    public static String password() {
        String v = resolve(PASSWORD_ENV, PASSWORD_KEY);
        return v == null || v.isBlank() ? null : v;   // do NOT trim — passwords may legitimately have spaces
    }

    /**
     * Force the offline (unverified) auth path even on a premium account — for testing the unverified flow.
     * Normal offline accounts fall back automatically when the Mojang handshake fails; this just skips the
     * attempt. Only takes effect if the relay itself has {@code ALLOW_UNVERIFIED} enabled.
     */
    public static boolean forceOffline() {
        String v = resolve(OFFLINE_ENV, OFFLINE_KEY);
        if (v == null) return false;
        v = v.trim().toLowerCase();
        return v.equals("true") || v.equals("1") || v.equals("yes");
    }

    /**
     * First-launch convenience: if the relay keys aren't set yet, seed them from the baked defaults so an
     * invited pilot tester doesn't have to hand-edit {@code boss-pvp.properties}. No-op when the baked defaults
     * are empty (the public build) or when the user has already set the keys (never clobbers a manual edit).
     * Environment-variable overrides still win at read time via {@link #resolve}. Called once from
     * {@code RelayManager.init()} before the pilot gate is evaluated.
     */
    public static void autoPopulateDefaults() {
        autoPopulate(k -> BossPvpAddon.getConfigString(k, null), BossPvpAddon::setConfigString,
            DEFAULT_URL, DEFAULT_INVITE);
    }

    /**
     * Pure seeding logic with the config get/set injected, so first-launch behavior is unit-testable without a
     * live client: writes {@code relay.url}/{@code relay.invite} from the defaults only when they are currently
     * unset. Each key is seeded INDEPENDENTLY — the public build bakes a URL but no invite, so a blank invite
     * default must not stop the URL from being seeded. A blank default simply writes nothing for that key.
     * Package-private for testing.
     */
    static void autoPopulate(java.util.function.Function<String, String> get,
                             java.util.function.BiConsumer<String, String> set,
                             String defUrl, String defInvite) {
        try {
            if (!blank(defUrl) && blank(get.apply(URL_KEY))) set.accept(URL_KEY, defUrl);
            if (!blank(defInvite) && blank(get.apply(INVITE_KEY))) set.accept(INVITE_KEY, defInvite);
        } catch (Throwable ignored) {
            // a config write must never break addon init
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String resolve(String envKey, String cfgKey) {
        try {
            String env = System.getenv(envKey);
            if (env != null && !env.isBlank()) return env;
        } catch (Throwable ignored) {
            // fall through to config file
        }
        try {
            String v = BossPvpAddon.getConfigString(cfgKey, null);
            if (v != null && !v.isBlank()) return v;
        } catch (Throwable ignored) {
            // treat as unset
        }
        return null;
    }
}
