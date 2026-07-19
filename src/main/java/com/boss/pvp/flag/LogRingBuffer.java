package com.boss.pvp.flag;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Rolling in-memory ring buffer of the client's own recent log lines, fed by a Log4j2 appender attached to
 * the root logger. It taps the logger directly rather than reading the log FILE (which is racy and may be
 * locked while being written), so it is always current. {@link FlagReporter} snapshots it around a
 * kick/crash to attach a short before/after excerpt to the report. Bounded to the last {@value #MAX_LINES}
 * lines so memory stays trivial. All log text is passed through {@link LogSanitizer} before it ever leaves
 * the client.
 */
public final class LogRingBuffer {

    private LogRingBuffer() {}

    private static final int MAX_LINES = 100;
    private static final int MAX_LINE_CHARS = 300;   // clip a pathological single line before storing

    private static final Object LOCK = new Object();
    private static final Deque<String> BUF = new ArrayDeque<>(MAX_LINES + 1);
    private static volatile boolean installed = false;

    /**
     * Attach the capture appender to the root logger. Safe to call repeatedly; installs at most once.
     *
     * <p>This uses the canonical Log4j2 runtime-appender path — resolve the active {@link LoggerContext},
     * register the appender on its {@link Configuration}, add it to the ROOT logger config, then
     * {@code updateLoggers()} so the running context re-resolves and events actually route to it. The previous
     * implementation used {@code LogManager.getRootLogger().addAppender(...)} guarded by an
     * {@code instanceof core.Logger} check: on the Minecraft/Fabric runtime that path added the appender to a
     * LoggerConfig map without updating the context (so no events arrived), and the guard could silently
     * no-op — either way the buffer stayed empty and every flag report shipped with no log. Hence the rewrite.
     */
    public static synchronized void install() {
        if (installed) return;
        try {
            LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
            Configuration cfg = ctx.getConfiguration();
            AbstractAppender appender =
                new AbstractAppender("BossPvpFlagRing", null, null, true, Property.EMPTY_ARRAY) {
                    @Override public void append(LogEvent event) {
                        try {
                            String msg = event.getMessage() == null ? "" : event.getMessage().getFormattedMessage();
                            add(event.getLevel() + " [" + event.getLoggerName() + "] " + msg);
                        } catch (Throwable ignored) {
                            // never let log capture break logging
                        }
                    }
                };
            appender.start();
            cfg.addAppender(appender);
            cfg.getRootLogger().addAppender(appender, null, null);   // null level/filter -> capture everything at root
            ctx.updateLoggers();
            installed = true;
            System.out.println("[boss-pvp/flags] log ring buffer installed on root logger");
        } catch (Throwable t) {
            System.out.println("[boss-pvp/flags] log ring buffer install failed: " + t);
        }
    }

    /** Current number of buffered lines — a cheap health check for verifying capture is live. */
    public static int size() {
        synchronized (LOCK) {
            return BUF.size();
        }
    }

    static void add(String line) {
        if (line == null) return;
        if (line.length() > MAX_LINE_CHARS) line = line.substring(0, MAX_LINE_CHARS) + "…";
        synchronized (LOCK) {
            BUF.addLast(line);
            while (BUF.size() > MAX_LINES) BUF.removeFirst();
        }
    }

    /** Current buffer contents, oldest first. */
    public static List<String> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(BUF);
        }
    }
}
