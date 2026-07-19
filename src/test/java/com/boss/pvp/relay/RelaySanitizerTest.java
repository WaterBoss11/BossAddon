package com.boss.pvp.relay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client-side relay scrubbing — the same discipline the relay server applies, tested here because the client
 * must not depend solely on server-side scrubbing. Confirms control chars, Minecraft §-formatting, and mass
 * mentions are stripped/neutralized and the length is capped, all before text leaves the client.
 */
class RelaySanitizerTest {

    private static final String ZWSP = "​";

    @Test
    void stripsControlCharsAndNewlines() {
        assertEquals("abc", RelaySanitizer.sanitize("abc"));
        assertEquals("onetwo", RelaySanitizer.sanitize("one\ntwo"));   // control chars removed (matches relay server)
        assertEquals("tabgone", RelaySanitizer.sanitize("tab\tgone"));
    }

    @Test
    void stripsMinecraftFormattingCodes() {
        assertEquals("red text", RelaySanitizer.sanitize("§cred §rtext"));
        assertEquals("plain", RelaySanitizer.sanitize("§§plain"));   // lone/leftover section signs removed
    }

    @Test
    void neutralizesMassMentions() {
        String out = RelaySanitizer.sanitize("hi @everyone and @here");
        assertFalse(out.contains("@everyone"), "raw @everyone must not survive");
        assertFalse(out.contains("@here"), "raw @here must not survive");
        assertTrue(out.contains("@" + ZWSP + "everyone"));
        assertTrue(out.contains("@" + ZWSP + "here"));
    }

    @Test
    void capsLength() {
        String huge = "x".repeat(RelaySanitizer.MAX_LEN + 50);
        assertEquals(RelaySanitizer.MAX_LEN, RelaySanitizer.sanitize(huge).length());
    }

    @Test
    void nullAndBlank() {
        assertEquals("", RelaySanitizer.sanitize(null));
        assertTrue(RelaySanitizer.isBlank("   "));
        assertTrue(RelaySanitizer.isBlank("§a§b"));   // only formatting -> blank
        assertFalse(RelaySanitizer.isBlank("real"));
    }
}
