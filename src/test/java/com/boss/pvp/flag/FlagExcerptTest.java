package com.boss.pvp.flag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the excerpt that becomes the .txt attachment: the new attachment delivery must NOT bypass
 * {@link LogSanitizer}. {@link FlagReporter#buildExcerpt} is the exact pipeline the attachment ships, so
 * these prove raw IPs/domains/paths are scrubbed before the log ever leaves the client, and that the
 * newest-first budget is honoured.
 */
class FlagExcerptTest {

    @Test
    void sanitizesEveryLineBeforeItBecomesAnAttachment() {
        List<String> raw = List.of(
            "INFO [net] Connecting to play.hypixel.net, 25565",
            "INFO [net] remote 203.0.113.7:25565 established",
            "INFO [game] loading C:\\Users\\Esad\\AppData\\.minecraft");
        String out = FlagReporter.buildExcerpt(raw, "Esad", 15_000);
        assertFalse(out.contains("203.0.113.7"), "raw IP must be scrubbed");
        assertFalse(out.contains("hypixel.net"), "server host must be scrubbed");
        assertFalse(out.contains("Esad"), "the local username must be scrubbed");
        assertTrue(out.contains("[ip removed]") || out.contains("[host removed]"), "redaction markers present");
        assertTrue(out.contains("[user]"), "windows username path segment redacted");
    }

    @Test
    void keepsNewestLinesWithinBudget() {
        // Tiny budget: only the most recent line(s) survive, oldest dropped.
        List<String> raw = List.of("oldest line here", "middle line here", "newest kept line");
        String out = FlagReporter.buildExcerpt(raw, null, 20);
        assertTrue(out.contains("newest kept line"), "newest line kept");
        assertFalse(out.contains("oldest line here"), "oldest dropped when over budget");
    }

    @Test
    void emptyOrNullBufferYieldsNull() {
        assertNull(FlagReporter.buildExcerpt(List.of(), "u", 15_000));
        assertNull(FlagReporter.buildExcerpt(null, "u", 15_000));
    }
}
