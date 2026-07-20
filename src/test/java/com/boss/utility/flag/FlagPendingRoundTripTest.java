package com.boss.utility.flag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The crash-path disk round trip: onCrash writes {@code pending-flags.jsonl}, next launch flushPending reads it
 * back and re-POSTs with the .txt attachment. Proves the sanitized log (newlines/tabs/unicode) survives the
 * write→flush trip byte for byte — the previously-untested path where a missing attachment could hide.
 * (BossUtility's line format is {@code json\tbase64-log}; it posts to its single webhook, so there is no url field.)
 */
class FlagPendingRoundTripTest {

    @Test
    void crashLineRoundTripsPreservingTheAttachmentLog() {
        String json = "{\"embeds\":[{\"title\":\"\\u26a0 Crash\",\"description\":\"NPE\\tat mesh\\nline2\"}]}";
        String log = "INFO [main] Preparing crash report\n"
            + "WARN [net] disconnect at [ip removed]\n"
            + "ERROR [render] NPE building section\twith tab\n"
            + "ünïcödé ✓ and § stripped already";

        String line = FlagReporter.encodePending(json, log);
        assertFalse(line.contains("\n"), "encoded line must be single-line (log is base64'd)");

        String[] e = FlagReporter.parsePending(line);
        assertEquals(json, e[0], "embed json survives");
        assertEquals(log, e[1], "the attachment log survives the disk round trip EXACTLY");
    }

    @Test
    void nullAndBlankLogRoundTripAsNoAttachment() {
        assertNull(FlagReporter.parsePending(FlagReporter.encodePending("{}", null))[1]);
        assertNull(FlagReporter.parsePending(FlagReporter.encodePending("{}", ""))[1]);
    }

    @Test
    void legacyBareJsonLineStillFlushes() {
        String[] e = FlagReporter.parsePending("{\"legacy\":true}");
        assertEquals("{\"legacy\":true}", e[0]);
        assertNull(e[1]);
    }

    @Test
    void blankOrNullLineParsesToNull() {
        assertNull(FlagReporter.parsePending(""));
        assertNull(FlagReporter.parsePending("   "));
        assertNull(FlagReporter.parsePending(null));
    }
}
