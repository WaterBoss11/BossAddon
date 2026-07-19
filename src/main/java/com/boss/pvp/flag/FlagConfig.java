package com.boss.pvp.flag;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Resolves the flags webhook URL (the boss-pvp-flags channel) for the running client.
 *
 * <p><b>Shipped default.</b> Flag reporting is on for every user out of the box: the webhook is baked into
 * the addon here ({@link #DEFAULT_WEBHOOK}, committed — not gitignored) so all clients report to the same
 * shared channel. Users opt out via the in-game "Report crashes & kicks" toggle (see the flag report
 * module), not by editing config.
 *
 * <p><b>Optional local override.</b> For a private/dev channel, a machine-local source can override the
 * baked default (never committed): {@code BOSS_PVP_FLAGS_WEBHOOK} env var, else
 * {@code <run>/config/boss-pvp/flags.properties} with {@code webhook=...}. This is a LOCAL CLIENT override,
 * not a GitHub Actions secret — the commit/star notifier's {@code secrets.*} mechanism does not exist inside
 * a running Minecraft client.
 *
 * <p>Precedence: env var → local file → baked default. If the resolved value is blank or still the
 * unfilled placeholder, reporting is silently disabled.
 */
public final class FlagConfig {

    private FlagConfig() {}

    // Baked-in shipped webhook for the shared boss-pvp-flags channel ("Flagged BOSSPVP"). PUBLIC by design
    // (it ships in the jar) — see the README privacy section + abuse note. If this is ever reset to a value
    // containing the placeholder marker (REPLACE_ME), reporting auto-disables so no client POSTs to a bogus URL.
    static final String DEFAULT_WEBHOOK =
        "https://discord.com/api/webhooks/1527840817845375197/_F72OBVwgvrivOxA_EGjTotDPm0Th9aRSzMU1n42UtpzlxOQlVzbVufvbdQMTRpo_urb";

    // Combined "Boss Utility+PVP Flags" channel — used ONLY when both boss-pvp and BossUtility are installed,
    // where boss-pvp reports one merged embed here instead of to its own channel. Same placeholder rule.
    static final String DUAL_WEBHOOK =
        "https://discord.com/api/webhooks/1527909302906523809/k19JESighYZlMVchIeu-ZTuZqbSQiMsgzs4P8cZeAW5hXGzwOUVX3PBNGsEKAbK6cuhZ";

    private static final String PLACEHOLDER_MARKER = "REPLACE_ME";
    private static final String ENV_KEY = "BOSS_PVP_FLAGS_WEBHOOK";
    private static final String DUAL_ENV_KEY = "BOSS_PVP_FLAGS_DUAL_WEBHOOK";
    private static final String CONFIG_REL = "boss-pvp/flags.properties";

    private static volatile boolean loaded = false;
    private static volatile String webhook = null;
    private static volatile boolean dualLoaded = false;
    private static volatile String dualWebhook = null;

    /** The resolved webhook URL, or {@code null} if not configured. Cached after first resolution. */
    public static String webhook() {
        if (!loaded) resolve();
        return webhook;
    }

    public static boolean isConfigured() {
        return webhook() != null;
    }

    /** The combined-channel webhook (both addons installed), or null if not configured. */
    public static String dualWebhook() {
        if (!dualLoaded) resolveDual();
        return dualWebhook;
    }

    public static boolean isDualConfigured() {
        return dualWebhook() != null;
    }

    /** The path a local override lives at (for logging/help text). */
    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_REL);
    }

    private static synchronized void resolve() {
        if (loaded) return;
        try {
            String override = resolveOverride(ENV_KEY, "webhook");
            webhook = usable(override != null ? override : DEFAULT_WEBHOOK);
        } catch (Exception e) {
            webhook = null;
        } finally {
            loaded = true;
        }
    }

    private static synchronized void resolveDual() {
        if (dualLoaded) return;
        try {
            String override = resolveOverride(DUAL_ENV_KEY, "dualWebhook");
            dualWebhook = usable(override != null ? override : DUAL_WEBHOOK);
        } catch (Exception e) {
            dualWebhook = null;
        } finally {
            dualLoaded = true;
        }
    }

    private static String resolveOverride(String envKey, String propKey) {
        try {
            String env = System.getenv(envKey);
            if (env != null && !env.isBlank()) return env.trim();
            Path p = configPath();
            if (Files.isRegularFile(p)) {
                Properties props = new Properties();
                try (var in = Files.newInputStream(p)) {
                    props.load(in);
                }
                String w = props.getProperty(propKey);
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
