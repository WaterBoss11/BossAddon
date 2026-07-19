package com.boss.pvp.update;

import com.boss.pvp.BossPvpAddon;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Launch-time, read-only update check. On init it fires a single async GET to the GitHub "latest release" API
 * for {@link #REPO}, compares the returned tag against the version baked into this build, and — if we're
 * behind — shows a one-time chat message (with a link to the release page) plus a small HUD indicator
 * ({@code UpdateHud}).
 *
 * <p><b>Scope: read-only.</b> Nothing is ever downloaded, extracted, or installed — the check only reads a tag
 * name and tells the user. It is fire-and-forget and fully wrapped in try/catch: if GitHub is unreachable or
 * anything fails, it fails silently and never blocks or throws into startup.
 *
 * <p>No data leaves the client beyond the plain GET itself (the same request any browser makes visiting the
 * releases page — no username, no telemetry). Users can opt out entirely via the "Update Checker" module
 * toggle; when it's off, no GitHub request is made at all.
 */
public final class UpdateChecker {

    private UpdateChecker() {}

    public static final String REPO = "WaterBoss11/boss-pvp";
    private static final String MOD_ID = "boss-pvp";
    private static final String LATEST_API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static volatile String currentVersion = "";
    private static volatile String latestVersion = null;
    private static volatile boolean outdated = false;
    private static volatile boolean notified = false;

    private static void log(String m) { System.out.println("[boss-pvp/update] " + m); }

    /**
     * Call once on addon init, after modules are registered (so the opt-out toggle is readable). Reads the
     * baked version and, if the toggle is on, fires the async check. Never throws.
     */
    public static void init() {
        try {
            currentVersion = readModVersion();
            if (!toggleOn()) {
                log("update check disabled by toggle — no GitHub request made.");
                return;
            }
            fireAsync();
        } catch (Throwable t) {
            log("init failed (ignored): " + t);   // must never block startup
        }
    }

    private static void fireAsync() {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(LATEST_API))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", MOD_ID + "-update-check")   // GitHub requires a UA; carries no personal info
                .GET()
                .build();
            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(UpdateChecker::onResponse)
                .exceptionally(e -> { log("check failed (ignored): " + e.getMessage()); return null; });
        } catch (Throwable t) {
            log("could not start check (ignored): " + t);
        }
    }

    private static void onResponse(HttpResponse<String> res) {
        try {
            if (res.statusCode() / 100 != 2) { log("GitHub -> " + res.statusCode() + " (ignored)"); return; }
            String tag = ReleaseJson.extractTagName(res.body());
            if (tag == null) { log("no tag_name in response (ignored)"); return; }
            latestVersion = VersionCompare.normalize(tag);
            outdated = VersionCompare.isOutdated(currentVersion, latestVersion);
            log("current=" + currentVersion + " latest=" + latestVersion + " outdated=" + outdated);
        } catch (Throwable t) {
            log("response handling failed (ignored): " + t);
        }
    }

    /**
     * Call every client tick while in-world. Shows the one-time chat notice the first time the player exists
     * after an out-of-date result. Once per session — it never re-nags.
     */
    public static void tick(Minecraft mc) {
        try {
            if (!outdated || notified || mc == null || mc.player == null) return;
            notified = true;
            mc.player.sendSystemMessage(buildChatMessage());
        } catch (Throwable t) {
            notified = true;   // don't retry-spam a broken message
            log("notify failed (ignored): " + t);
        }
    }

    private static Component buildChatMessage() {
        Component link = Component.literal(RELEASES_PAGE)
            .withStyle(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create(RELEASES_PAGE)))
                .withUnderlined(true)
                .withColor(ChatFormatting.AQUA));
        return Component.literal("")
            .append(Component.literal("[Boss's PVP] ").withStyle(ChatFormatting.GOLD))
            .append(Component.literal("Update available — you have ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("v" + currentVersion).withStyle(ChatFormatting.RED))
            .append(Component.literal(", latest is ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("v" + latestVersion).withStyle(ChatFormatting.GREEN))
            .append(Component.literal(". Get it: ").withStyle(ChatFormatting.GRAY))
            .append(link);
    }

    // ---- state for the HUD indicator ----------------------------------------------------------------

    public static boolean isOutdated() { return outdated; }
    public static String currentVersion() { return currentVersion; }
    public static String latestVersion() { return latestVersion; }

    /** The opt-out toggle; fail-open (default is to check) since the module is registered before init runs. */
    private static boolean toggleOn() {
        try {
            return BossPvpAddon.updateCheck == null || BossPvpAddon.updateCheck.checkEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /** The version baked into this build (from fabric.mod.json / mod-version), normalized, or "" if unknown. */
    private static String readModVersion() {
        try {
            return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> VersionCompare.normalize(c.getMetadata().getVersion().getFriendlyString()))
                .orElse("");
        } catch (Throwable t) {
            return "";
        }
    }
}
