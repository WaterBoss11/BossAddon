package com.boss.pvp.flag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The crash-path disk round trip: onCrash writes {@code pending-flags.jsonl}, next launch flushPending reads it
 * back and re-POSTs with the .txt attachment. This exercises the exact encode/decode that carries the log
 * across a crash — the path that was previously untested and where a missing attachment could silently be
 * introduced. Proves the sanitized log (including newlines/tabs/unicode) survives the write→flush trip byte for
 * byte, and that null-log and legacy lines behave.
 */
class FlagPendingRoundTripTest {

    @Test
    void crashLineRoundTripsPreservingTheAttachmentLog() {
        String url = "https://discord.com/api/webhooks/1527/abcDEF-_";
        // Gson-style compact json (tabs/newlines already escaped as \\t/\\n by Gson, so single-line on disk).
        String json = "{\"embeds\":[{\"title\":\"\\u26a0 Crash\",\"description\":\"NPE\\tat mesh\\nline2\"}]}";
        // A realistic sanitized excerpt with real newlines + a tab + unicode — must survive intact.
        String log = "INFO [main] Preparing crash report\n"
            + "WARN [net] disconnect at [ip removed]\n"
            + "ERROR [render] NPE building section\twith tab\n"
            + "ünïcödé ✓ and § stripped already";

        String line = FlagReporter.encodePending(url, json, log);
        assertFalse(line.contains("\n"), "encoded line must be single-line (log is base64'd), so readAllLines can't split it");

        String[] e = FlagReporter.parsePending(line);
        assertEquals(url, e[0], "webhook url survives");
        assertEquals(json, e[1], "embed json survives");
        assertEquals(log, e[2], "the attachment log survives the disk round trip EXACTLY");
    }

    @Test
    void nullLogRoundTripsAsNoAttachment() {
        String[] e = FlagReporter.parsePending(FlagReporter.encodePending("u", "{}", null));
        assertEquals("u", e[0]);
        assertEquals("{}", e[1]);
        assertNull(e[2], "no log -> no attachment on flush");
    }

    @Test
    void blankLogRoundTripsAsNoAttachment() {
        // An empty string encodes to an empty base64 field, which decodes back to null (no attachment).
        String[] e = FlagReporter.parsePending(FlagReporter.encodePending("u", "{}", ""));
        assertNull(e[2]);
    }

    @Test
    void legacyBareJsonLineFallsBackToIndividualWebhook() {
        String[] e = FlagReporter.parsePending("{\"legacy\":true}");
        assertNull(e[0], "no url in a legacy line -> flush uses FlagConfig.webhook()");
        assertEquals("{\"legacy\":true}", e[1]);
        assertNull(e[2]);
    }

    @Test
    void blankOrNullLineParsesToNull() {
        assertNull(FlagReporter.parsePending(""));
        assertNull(FlagReporter.parsePending("   "));
        assertNull(FlagReporter.parsePending(null));
    }
}
