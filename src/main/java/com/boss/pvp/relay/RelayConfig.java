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
    private static final String URL_ENV = "BOSS_PVP_RELAY_URL";
    private static final String INVITE_ENV = "BOSS_PVP_RELAY_INVITE";

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
