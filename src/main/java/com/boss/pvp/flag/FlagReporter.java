package com.boss.pvp.flag;

import com.boss.pvp.BossPvpAddon;

import net.minecraft.CrashReport;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Fires "flag" reports to the boss-pvp-flags Discord webhook ({@link FlagConfig}) when the client is kicked,
 * packet-kicked, or crashes — with a snapshot of the modules enabled at that moment.
 *
 * <p>Delivery is fire-and-forget on an async HTTP client: it never blocks the game thread and never throws
 * into the caller (a failed or unreachable Discord is logged and swallowed). A dedupe window collapses a
 * kick→reconnect→kick loop so the channel isn't spammed.
 *
 * <p>Crashes get an extra guarantee: because a hard crash can kill the JVM before an HTTP call finishes, the
 * crash payload is written to disk <i>synchronously</i> first ({@code config/boss-pvp/pending-flags.jsonl})
 * and only then POSTed best-effort. {@link #init()} on the next launch flushes anything left on disk, so a
 * crash report survives even if the immediate POST never completed.
 */
public final class FlagReporter {

    private FlagReporter() {}

    private static final String LOGO = "https://raw.githubusercontent.com/WaterBoss11/boss-pvp/master/assets/MainLogo.png";
    private static final String REPO = "WaterBoss11/boss-pvp";
    private static final String PENDING_REL = "boss-pvp/pending-flags.jsonl";

    private static final long DEDUP_WINDOW_MS = 30_000L;
    private static final long PACKET_KICK_WINDOW_MS = 2_000L;

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static final Object LOCK = new Object();
    private static String lastKey = null;
    private static long lastMs = 0L;

    // A server-sent Disconnect packet lands here just before the generic onDisconnect fires, so we can tell
    // a packet-kick apart from a connection-loss kick.
    private static volatile Component packetKickReason = null;
    private static volatile long packetKickMs = 0L;

    private static void log(String m) { System.out.println("[boss-pvp/flags] " + m); }

    /** Call once on addon init: flush any crash reports persisted before a prior hard crash. */
    public static void init() {
        if (!FlagConfig.isConfigured()) {
            log("flags webhook not configured — reporting disabled. To enable, set env "
                + "BOSS_PVP_FLAGS_WEBHOOK or create " + FlagConfig.configPath() + " with webhook=<url>");
            return;
        }
        flushPending();
    }

    // ---- disconnect path -----------------------------------------------------------------------------

    /** Mixin hook: the server sent an explicit Disconnect packet (packet-kick). */
    public static void markPacketKick(Component reason) {
        packetKickReason = reason;
        packetKickMs = System.currentTimeMillis();
    }

    /** Mixin hook: the connection dropped for any reason. Classifies against a recent packet-kick. */
    public static void onDisconnect(Component reason) {
        boolean isPacketKick = packetKickReason != null
            && System.currentTimeMillis() - packetKickMs < PACKET_KICK_WINDOW_MS;
        Component effective = isPacketKick ? packetKickReason : reason;
        packetKickReason = null;

        FlagPayload.Type type = isPacketKick ? FlagPayload.Type.PACKET_KICK : FlagPayload.Type.KICK;
        report(type, textOf(effective));
    }

    // ---- crash path ----------------------------------------------------------------------------------

    /** Mixin hook: a crash report is being generated. Persist to disk, then best-effort POST. */
    public static void onCrash(CrashReport report) {
        try {
            if (!reportingEnabled()) return;
            String reason = crashReason(report);
            String json = FlagPayload.build(FlagPayload.Type.CRASH, reason,
                BossPvpAddon.enabledModuleSummary(), Instant.now().toString(), LOGO, REPO);
            writePending(json);   // survives JVM death
            postAsync(json);      // may not finish before the JVM dies — that's what the disk copy is for
        } catch (Throwable t) {
            // A crash reporter must never make the crash worse.
            log("crash report hook failed: " + t);
        }
    }

    // ---- shared reporting ----------------------------------------------------------------------------

    private static void report(FlagPayload.Type type, String reason) {
        try {
            if (!reportingEnabled()) return;
            String key = type.name() + "|" + (reason == null ? "" : reason);
            long now = System.currentTimeMillis();
            synchronized (LOCK) {
                if (isDuplicate(lastKey, lastMs, key, now, DEDUP_WINDOW_MS)) return;
                lastKey = key;
                lastMs = now;
            }
            String json = FlagPayload.build(type, reason, BossPvpAddon.enabledModuleSummary(),
                Instant.now().toString(), LOGO, REPO);
            postAsync(json);
        } catch (Throwable t) {
            log("report failed: " + t);
        }
    }

    /** On unless the webhook is unconfigured or the user turned the report toggle off (fail-open on error). */
    private static boolean reportingEnabled() {
        if (!FlagConfig.isConfigured()) return false;
        try {
            return BossPvpAddon.flagReport == null || BossPvpAddon.flagReport.reportingEnabled();
        } catch (Throwable t) {
            return true;   // never let a settings read suppress a crash report
        }
    }

    /** Pure dedupe test: same key within {@code windowMs} of the last send is a duplicate. */
    static boolean isDuplicate(String lastKey, long lastMs, String key, long nowMs, long windowMs) {
        return key != null && key.equals(lastKey) && (nowMs - lastMs) < windowMs;
    }

    // ---- capture helpers -----------------------------------------------------------------------------

    private static String textOf(Component c) {
        try {
            return c == null ? null : c.getString();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String crashReason(CrashReport report) {
        String title = report.getTitle();
        Throwable ex = report.getException();
        String exLine = ex == null ? "" : ex.getClass().getSimpleName()
            + (ex.getMessage() != null ? ": " + ex.getMessage() : "");
        return exLine.isEmpty() ? title : title + " — " + exLine;
    }

    // ---- delivery + persistence ----------------------------------------------------------------------

    private static void postAsync(String json) {
        String url = FlagConfig.webhook();
        if (url == null) return;
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
            CompletableFuture<HttpResponse<String>> fut =
                HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString());
            fut.thenAccept(res -> {
                if (res.statusCode() / 100 != 2) {
                    log("Discord flags webhook -> " + res.statusCode() + " " + trim(res.body()));
                }
            }).exceptionally(e -> {
                log("Discord flags webhook error: " + e.getMessage());
                return null;
            });
        } catch (Throwable t) {
            log("post failed: " + t);
        }
    }

    private static String trim(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200);
    }

    private static Path pendingPath() {
        return FlagConfig.configPath().resolveSibling("pending-flags.jsonl");
    }

    private static synchronized void writePending(String json) {
        try {
            Path p = pendingPath();
            Files.createDirectories(p.getParent());
            // one payload per line, newlines in JSON already escaped by Gson
            Files.writeString(p, json + System.lineSeparator(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable t) {
            log("could not persist pending flag: " + t);
        }
    }

    private static void flushPending() {
        try {
            Path p = pendingPath();
            if (!Files.isRegularFile(p)) return;
            List<String> lines = new ArrayList<>(Files.readAllLines(p, StandardCharsets.UTF_8));
            Files.delete(p);   // delete first so a failed resend can't loop forever
            int n = 0;
            for (String line : lines) {
                if (line != null && !line.isBlank()) { postAsync(line); n++; }
            }
            if (n > 0) log("flushed " + n + " pending crash flag(s) from last session");
        } catch (Throwable t) {
            log("flush of pending flags failed: " + t);
        }
    }
}
