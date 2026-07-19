package com.boss.pvp.relay;

import com.boss.pvp.BossPvpAddon;

/**
 * Resolves the chat-relay connection settings from the local client config ({@code boss-pvp.properties}, via
 * {@link BossPvpAddon#getConfigString}). Environment variables override the file so a tester can point at a
 * dev relay without editing config.
 *
 * <p><b>Pilot gate.</b> The relay is CLOSED-PILOT software: it is inert unless the user has explicitly set
 * BOTH a relay URL and an invite code they were given. A default public install has neither, so it never
 * connects and shows no relay UI — the feature does not reach the general boss-pvp userbase yet. (The relay
 * server enforces its own Mojang-verified allowlist on top of this; the invite here is only a client-side
 * "don't even try unless you were invited" gate, never the security boundary.)
 *
 * <p>Keys ({@code boss-pvp.properties}) / env vars:
 * <ul>
 *   <li>{@code relay.url} / {@code BOSS_PVP_RELAY_URL} — e.g. {@code wss://relay.example.com}</li>
 *   <li>{@code relay.invite} / {@code BOSS_PVP_RELAY_INVITE} — the pilot invite code</li>
 * </ul>
 */
public final class RelayConfig {

    private RelayConfig() {}

    private static final String URL_KEY = "relay.url";
    private static final String INVITE_KEY = "relay.invite";
    private static final String OFFLINE_KEY = "relay.offline";

    // Baked first-launch defaults. EMPTY in the public build → auto-populate is a
    // no-op and normal installs are unaffected. A PILOT build overrides these two
    // constants (only) to seed a specific invited tester's relay config on first
    // run, so they don't have to hand-edit boss-pvp.properties.
    static final String DEFAULT_URL = "";
    static final String DEFAULT_INVITE = "";
    private static final String URL_ENV = "BOSS_PVP_RELAY_URL";
    private static final String INVITE_ENV = "BOSS_PVP_RELAY_INVITE";
    private static final String OFFLINE_ENV = "BOSS_PVP_RELAY_OFFLINE";

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

    /** True only when BOTH a valid URL and an invite code are present — the closed-pilot gate. */
    public static boolean isConfigured() {
        return url() != null && invite() != null;
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
     * unset. Package-private for testing.
     */
    static void autoPopulate(java.util.function.Function<String, String> get,
                             java.util.function.BiConsumer<String, String> set,
                             String defUrl, String defInvite) {
        if (blank(defUrl) || blank(defInvite)) return;   // public build: nothing baked -> no-op
        try {
            if (blank(get.apply(URL_KEY))) set.accept(URL_KEY, defUrl);
            if (blank(get.apply(INVITE_KEY))) set.accept(INVITE_KEY, defInvite);
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
