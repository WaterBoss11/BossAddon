package com.boss.utility.flag;

import com.boss.utility.BossUtilityAddon;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fires "flag" reports to the BossUtility flags Discord webhook ({@link FlagConfig}) when the client is
 * kicked, packet-kicked, or crashes — with a snapshot of the modules enabled at that moment. Reports carry
 * only the event, reason, and enabled modules; never a server name or IP.
 *
 * <p>Delivery is fire-and-forget on an async HTTP client: it never blocks the game thread and never throws
 * into the caller (a failed or unreachable Discord is logged and swallowed). A dedupe window collapses a
 * kick→reconnect→kick loop so the channel isn't spammed.
 *
 * <p>Crashes get an extra guarantee: because a hard crash can kill the JVM before an HTTP call finishes, the
 * crash payload is written to disk <i>synchronously</i> first ({@code config/boss-utility/pending-flags.jsonl})
 * and only then POSTed best-effort. {@link #init()} on the next launch flushes anything left on disk.
 */
public final class FlagReporter {

    private FlagReporter() {}

    private static final String LOGO = "https://raw.githubusercontent.com/WaterBoss11/BossUtility/master/assets/MainLogo.png";
    private static final String REPO = "WaterBoss11/BossUtility";

    // Cross-addon dedup: boss-pvp is the designated reporter. When it's installed AND actually reporting,
    // it fires ONE combined embed (pulling BossUtility's modules via FlagBridge), so BossUtility suppresses
    // its own report here. If boss-pvp's toggle is off (or its bridge can't be reached), BossUtility falls
    // back to reporting independently — see suppressedByReporter().
    // The main addon's Fabric mod id (renamed boss-pvp -> bossaddon). BossUtility defers to it when present.
    private static final String REPORTER_MOD_ID = "bossaddon";
    private static final String REPORTER_BRIDGE = "com.boss.pvp.flag.FlagBridge";

    private static final long DEDUP_WINDOW_MS = 30_000L;
    private static final long PACKET_KICK_WINDOW_MS = 2_000L;

    // On a kick, wait a few seconds before finalizing so the log ring buffer also captures what happened
    // AFTER the event (the "before" is already in the buffer). Crashes can't wait — the JVM may be dying.
    private static final long AFTER_CAPTURE_MS = 4_000L;

    // The excerpt now ships as a downloadable .txt attachment (not a cramped embed field), so it can carry the
    // whole ring-buffer window rather than ~950 chars. Still bounded — the buffer itself is only ~100 lines —
    // so this cap just backstops a pathological run; it does NOT expand what is captured.
    private static final int MAX_LOG_FILE_CHARS = 15_000;

    private static final ScheduledExecutorService SCHED = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "boss-utility-flags-delay");
        t.setDaemon(true);
        return t;
    });

    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private static final Object LOCK = new Object();
    private static String lastKey = null;
    private static long lastMs = 0L;

    private static volatile Component packetKickReason = null;
    private static volatile long packetKickMs = 0L;

    private static void log(String m) { System.out.println("[boss-utility/flags] " + m); }

    /** Call once on addon init: start capturing logs, then flush any crash reports persisted before a crash. */
    public static void init() {
        LogRingBuffer.install();   // start the rolling log capture as early as possible
        if (!FlagConfig.isConfigured()) {
            log("flags webhook not configured — reporting disabled. To override, set env "
                + "BOSS_UTILITY_FLAGS_WEBHOOK or create " + FlagConfig.configPath() + " with webhook=<url>");
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

    /**
     * Mixin hook: the connection dropped. Classifies into a clear category using whether the server actively
     * sent a Disconnect packet (a recent {@link #markPacketKick}) and whether we had reached the in-world
     * (play) phase — see {@link FlagPayload#classifyDisconnect}. {@code inWorld} is supplied by the mixin from
     * the packet-listener type (play listener → in-world).
     */
    public static void onDisconnect(Component reason, boolean inWorld) {
        boolean serverSentReason = packetKickReason != null
            && System.currentTimeMillis() - packetKickMs < PACKET_KICK_WINDOW_MS;
        Component effective = serverSentReason ? packetKickReason : reason;
        packetKickReason = null;

        String reasonText = textOf(effective);
        FlagPayload.Type type = FlagPayload.classifyDisconnect(serverSentReason, inWorld, reasonText);
        report(type, reasonText);
    }

    // ---- crash path ----------------------------------------------------------------------------------

    /** Mixin hook: a crash report is being generated. Persist to disk, then best-effort POST. */
    public static void onCrash(CrashReport report) {
        try {
            if (!reportingEnabled() || suppressedByReporter()) return;
            String json = buildJson(FlagPayload.Type.CRASH, crashReason(report));
            String log = logExcerpt();          // sanitized excerpt, shipped as a .txt attachment
            if (log == null || log.isBlank()) {
                // Ring buffer empty at crash time — fall back to the crash's OWN stack trace so a crash report
                // still ships a useful attachment instead of just an embed.
                log = crashFallbackLog(report);
            }
            String file = logFilename();
            writePending(json, log);             // survives JVM death (json + log both persisted)
            postAsync(json, log, file);          // may not finish before the JVM dies — that's what the disk copy is for
        } catch (Throwable t) {
            log("crash report hook failed: " + t);
        }
    }

    // ---- shared reporting ----------------------------------------------------------------------------

    private static void report(FlagPayload.Type type, String reason) {
        try {
            if (!reportingEnabled() || suppressedByReporter()) return;
            String key = type.name() + "|" + (reason == null ? "" : reason);
            long now = System.currentTimeMillis();
            synchronized (LOCK) {
                if (isDuplicate(lastKey, lastMs, key, now, DEDUP_WINDOW_MS)) return;
                lastKey = key;
                lastMs = now;
            }
            // Delay finalizing so the ring buffer also captures the seconds AFTER the kick, then build+send.
            // The log excerpt is snapshotted HERE (post-delay) so it includes the aftermath, and shipped as a
            // .txt attachment on the same message.
            SCHED.schedule(() -> {
                try {
                    postAsync(buildJson(type, reason), logExcerpt(), logFilename());
                } catch (Throwable t) {
                    log("delayed report failed: " + t);
                }
            }, AFTER_CAPTURE_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            log("report failed: " + t);
        }
    }

    private static String buildJson(FlagPayload.Type type, String reason) {
        return FlagPayload.build(type, reason, localUsername(), BossUtilityAddon.enabledModuleSummary(),
            Instant.now().toString(), LOGO, REPO);
    }

    /** Filename for the attached log: {@code flag-log-<iso-timestamp>.txt} (colons/dots made filename-safe). */
    private static String logFilename() {
        return "flag-log-" + Instant.now().toString().replaceAll("[:.]", "-") + ".txt";
    }

    /** Snapshot the ring buffer and turn it into a sanitized excerpt for the .txt attachment (or null if empty). */
    private static String logExcerpt() {
        try {
            return buildExcerpt(LogRingBuffer.snapshot(), localUsername(), MAX_LOG_FILE_CHARS);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Sanitize every raw line through {@link LogSanitizer} and keep the most recent lines that fit
     * {@code maxChars} (newest-first budgeting). Pure/static so a unit test can prove the sanitizer still runs
     * on the excerpt BEFORE it becomes an attachment (i.e. the new delivery path did not bypass scrubbing).
     * Returns null for an empty/blank result. Package-private for testing.
     */
    static String buildExcerpt(List<String> rawLines, String username, int maxChars) {
        if (rawLines == null || rawLines.isEmpty()) return null;
        List<String> sanitized = new ArrayList<>(rawLines.size());
        for (String l : rawLines) sanitized.add(LogSanitizer.sanitize(l, username));
        int total = 0;
        int from = sanitized.size();
        for (int i = sanitized.size() - 1; i >= 0; i--) {   // newest-first until the budget is spent
            int len = sanitized.get(i).length() + 1;
            if (total + len > maxChars) break;
            total += len;
            from = i;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < sanitized.size(); i++) sb.append(sanitized.get(i)).append('\n');
        String out = sb.toString().strip();
        return out.isEmpty() ? null : out;
    }

    /** On unless the webhook is unconfigured or the user turned the report toggle off (fail-open on error). */
    private static boolean reportingEnabled() {
        if (!FlagConfig.isConfigured()) return false;
        try {
            return BossUtilityAddon.flagReport == null || BossUtilityAddon.flagReport.reportingEnabled();
        } catch (Throwable t) {
            return true;   // never let a settings read suppress a crash report
        }
    }

    /**
     * Whether BossUtility should stay silent because boss-pvp is handling this event. It suppresses ONLY
     * when boss-pvp is installed AND confirms (via its FlagBridge) that it is actually reporting — i.e. its
     * "Crash & Kick Reports" toggle is on. If boss-pvp's toggle is off, or the bridge is missing/unreadable
     * (older boss-pvp, reflection failure), BossUtility falls back to reporting independently: a possible
     * duplicate is safer than both addons going silent when the user still wants to be flagged.
     */
    private static boolean suppressedByReporter() {
        try {
            if (!FabricLoader.getInstance().isModLoaded(REPORTER_MOD_ID)) return false;
        } catch (Throwable t) {
            return false;   // detection failed -> report rather than risk silence
        }
        Boolean reporting = bridgeReportingEnabled(REPORTER_BRIDGE);
        return Boolean.TRUE.equals(reporting);   // suppress only when boss-pvp CONFIRMS it's reporting
    }

    /**
     * Reflectively read the reporter addon's {@code FlagBridge.isReportingEnabled()}. Returns null (never
     * throws) if the class/method is missing or mismatched — the caller then treats "unknown" as "not
     * reporting" and falls back to independent reporting. Package-private for testing.
     */
    static Boolean bridgeReportingEnabled(String bridgeClassName) {
        try {
            Class<?> c = Class.forName(bridgeClassName);
            Object res = c.getMethod("isReportingEnabled").invoke(null);
            if (res instanceof Boolean b) return b;
            return null;
        } catch (Throwable t) {
            return null;
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

    /** The reporting client's OWN Minecraft username (never another player's), or null if unavailable. */
    private static String localUsername() {
        try {
            var user = Minecraft.getInstance().getUser();
            return user == null ? null : user.getName();
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

    /**
     * Fallback log for the crash path when the ring buffer is empty: the crash's own stack trace, run through
     * the SAME sanitize+budget pipeline as a normal excerpt. Guarantees a crash report carries a useful
     * attachment even if live log capture never attached. Returns null if there's no exception to dump.
     */
    private static String crashFallbackLog(CrashReport report) {
        try {
            Throwable ex = report == null ? null : report.getException();
            if (ex == null) return null;
            java.io.StringWriter sw = new java.io.StringWriter();
            ex.printStackTrace(new java.io.PrintWriter(sw));
            List<String> lines = new ArrayList<>();
            for (String l : sw.toString().split("\\R")) lines.add(l);
            return buildExcerpt(lines, localUsername(), MAX_LOG_FILE_CHARS);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- delivery + persistence ----------------------------------------------------------------------

    private static void postAsync(String json) {
        postAsync(json, null, null);
    }

    /**
     * POST the report. When {@code logText} is present it is uploaded as a {@code multipart/form-data} message
     * with the sanitized log as a {@code files[0]} {@code .txt} attachment (Discord renders it as a downloadable
     * file); otherwise a plain JSON POST. Fire-and-forget, never throws into the caller.
     */
    private static void postAsync(String json, String logText, String filename) {
        String url = FlagConfig.webhook();
        if (url == null) return;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
            HttpRequest req;
            if (logText != null && !logText.isBlank()) {
                byte[] body = FlagPayload.multipartBody(json, logText, filename);
                req = b.header("Content-Type", "multipart/form-data; boundary=" + FlagPayload.BOUNDARY)
                       .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                       .build();
            } else {
                req = b.header("Content-Type", "application/json")
                       .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                       .build();
            }
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

    private static synchronized void writePending(String json, String logText) {
        try {
            Path p = pendingPath();
            Files.createDirectories(p.getParent());
            Files.writeString(p, encodePending(json, logText) + System.lineSeparator(),
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
                String[] e = parsePending(line);
                if (e == null) continue;
                postAsync(e[0], e[1], logFilename());   // e[1] (the log) rides along as the attachment
                n++;
            }
            if (n > 0) log("flushed " + n + " pending crash flag(s) from last session");
        } catch (Throwable t) {
            log("flush of pending flags failed: " + t);
        }
    }

    /**
     * Encode one pending-crash line: {@code "<json>\t<base64-log>"}. The log is base64'd so it can never
     * introduce a tab/newline (Gson output is single-line with tabs escaped), keeping the disk line a single,
     * unambiguously-splittable record — this is what carries the .txt attachment across a crash into the
     * next-launch flush. Pure/package-private so the round trip is unit-testable.
     */
    static String encodePending(String json, String logText) {
        String b64 = logText == null ? "" : Base64.getEncoder().encodeToString(logText.getBytes(StandardCharsets.UTF_8));
        return json + "\t" + b64;
    }

    /**
     * Parse a pending line back to {@code [json, logText]} (logText null when absent/blank; a legacy bare-json
     * line yields {@code [json, null]}). Returns null for a blank/unusable line. Pure/package-private for testing.
     */
    static String[] parsePending(String line) {
        if (line == null || line.isBlank()) return null;
        String[] parts = line.split("\t", 2);
        String logText = parts.length >= 2 ? decodeLog(parts[1]) : null;
        return new String[] { parts[0], logText };
    }

    private static String decodeLog(String b64) {
        if (b64 == null || b64.isEmpty()) return null;
        try {
            return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
