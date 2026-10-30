package com.boss.utility.flag;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Resolves reporting endpoints from machine-local configuration only.
 * BOSS_UTILITY_FLAGS_WEBHOOK takes precedence over config/boss-utility/flags.properties;
 * absent or placeholder endpoints disable reporting. No credentials ship in the addon.
 */
public final class FlagConfig {

    private FlagConfig() {}

    static final String DEFAULT_WEBHOOK = "";

    private static final String PLACEHOLDER_MARKER = "REPLACE_ME";
    private static final String ENV_KEY = "BOSS_UTILITY_FLAGS_WEBHOOK";
    private static final String CONFIG_REL = "boss-utility/flags.properties";

    private static volatile boolean loaded = false;
    private static volatile String webhook = null;

    /** The resolved webhook URL, or {@code null} if not configured. Cached after first resolution. */
    public static String webhook() {
        if (!loaded) resolve();
        return webhook;
    }

    public static boolean isConfigured() {
        return webhook() != null;
    }

    /** The path a local override lives at (for logging/help text). */
    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_REL);
    }

    private static synchronized void resolve() {
        if (loaded) return;
        try {
            String override = resolveOverride();
            webhook = usable(override != null ? override : DEFAULT_WEBHOOK);
        } catch (Exception e) {
            webhook = null;
        } finally {
            loaded = true;
        }
    }

    private static String resolveOverride() {
        try {
            String env = System.getenv(ENV_KEY);
            if (env != null && !env.isBlank()) return env.trim();
            Path p = configPath();
            if (Files.isRegularFile(p)) {
                Properties props = new Properties();
                try (var in = Files.newInputStream(p)) {
                    props.load(in);
                }
                String w = props.getProperty("webhook");
                if (w != null && !w.isBlank()) return w.trim();
            }
        } catch (Exception ignored) {
            // fall through to the baked default
        }
        return null;
    }

    /** Returns the URL if it is a real, filled-in webhook; otherwise null (blank or still a placeholder). */
    private static String usable(String url) {
        if (url == null || url.isBlank() || url.contains(PLACEHOLDER_MARKER)) return null;
        return url;
    }
}
