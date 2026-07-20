package com.boss.utility.flag;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Resolves the flags webhook URL (the BossUtility flags channel) for the running client.
 *
 * <p><b>Shipped default.</b> Flag reporting is on for every user out of the box: the webhook is baked into
 * the addon here ({@link #DEFAULT_WEBHOOK}, committed — not gitignored) so all clients report to the same
 * shared channel. Users opt out via the in-game "Report crashes &amp; kicks" toggle (the flag report
 * module), not by editing config.
 *
 * <p><b>Optional local override.</b> For a private/dev channel, a machine-local source can override the
 * baked default (never committed): {@code BOSS_UTILITY_FLAGS_WEBHOOK} env var, else
 * {@code <run>/config/boss-utility/flags.properties} with {@code webhook=...}. This is a LOCAL CLIENT
 * override, not a GitHub Actions secret.
 *
 * <p>Precedence: env var → local file → baked default. If the resolved value is blank or still the
 * unfilled placeholder, reporting is silently disabled.
 */
public final class FlagConfig {

    private FlagConfig() {}

    // Baked-in shipped webhook for the shared BossUtility flags channel ("Flagged Utility"). PUBLIC by design
    // (it ships in the jar) — see the README privacy section. If this is ever reset to a value containing the
    // placeholder marker (REPLACE_ME), reporting auto-disables so no client POSTs to a bogus URL.
    static final String DEFAULT_WEBHOOK =
        "https://discord.com/api/webhooks/1527841707259858998/l00lO5oSzoX5dqbbEGwbKlHc17hJCgpzY3B9DjlI24jTYEUPUJWuuIuvqe0I82FC-Imt";

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
