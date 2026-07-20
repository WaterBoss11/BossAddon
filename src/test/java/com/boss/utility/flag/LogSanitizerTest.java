package com.boss.utility.flag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link LogSanitizer} against realistic fake log lines — the privacy-critical piece. It must strip
 * IPv4/IPv6, common-TLD domains, and the Windows username in paths, while NOT mangling version numbers, log
 * timestamps, Java class/package names, dotted filenames, or {@code <init>} stack markers.
 */
class LogSanitizerTest {

    @Test
    void stripsIpv4WithAndWithoutPort() {
        assertEquals("peer [ip removed] down",
            LogSanitizer.sanitize("peer 192.168.1.50:25565 down"));
        assertEquals("peer [ip removed] closed",
            LogSanitizer.sanitize("peer 203.0.113.7 closed"));
    }

    @Test
    void doesNotStripVersionNumbers() {
        // three-part versions are not IPs
        assertEquals("Loaded Minecraft 1.20.2 (build 4)", LogSanitizer.sanitize("Loaded Minecraft 1.20.2 (build 4)"));
    }

    @Test
    void stripsIpv6ButKeepsTimestamps() {
        assertEquals("local [ip removed] up", LogSanitizer.sanitize("local fe80::1a2b up"));
        assertEquals("addr [ip removed]", LogSanitizer.sanitize("addr 2001:db8::ff00:42:8329"));
        // an HH:MM:SS log timestamp has only two colons and no "::" — must be left intact
        assertEquals("[12:34:56] [main] hello", LogSanitizer.sanitize("[12:34:56] [main] hello"));
    }

    @Test
    void stripsWindowsUsernameFromPath() {
        assertEquals("read C:\\Users\\[user]\\Downloads\\save.dat",
            LogSanitizer.sanitize("read C:\\Users\\esadb\\Downloads\\save.dat"));
        assertEquals("path c:/users/[user]/mods",
            LogSanitizer.sanitize("path c:/users/JohnDoe/mods"));
    }

    @Test
    void stripsCommonTldDomains() {
        assertEquals("[host removed] joined", LogSanitizer.sanitize("play.hypixel.net joined"));
        assertTrue(LogSanitizer.sanitize("resolving mc.some-server.com now").contains("[host removed]"));
    }

    @Test
    void keepsJavaClassAndPackageNamesAndFilenames() {
        // TLD-anchored domain regex must not eat class names / packages / dotted filenames
        String line = "at net.minecraft.client.Minecraft.<init>(Minecraft.java:123) via boss-pvp.mixins.json";
        String out = LogSanitizer.sanitize(line);
        assertTrue(out.contains("net.minecraft.client.Minecraft"), "class name preserved");
        assertTrue(out.contains("boss-pvp.mixins.json"), "dotted filename preserved");
        assertTrue(out.contains("<init>"), "<init> stack marker preserved");
        assertFalse(out.contains("[host removed]"));
    }

    @Test
    void redactsVanillaChatNames() {
        assertEquals("[CHAT] <[player]> hey there", LogSanitizer.sanitize("[CHAT] <Notch> hey there"));
    }

    @Test
    void combinedLineStripsEverything() {
        String line = "[15:02:11] <Steve> connect 51.83.128.9:25565 at C:\\Users\\esadb\\.minecraft srv play.2b2t.org";
        String out = LogSanitizer.sanitize(line);
        assertFalse(out.contains("Steve"));
        assertFalse(out.contains("51.83.128.9"));
        assertFalse(out.contains("esadb"));
        assertFalse(out.contains("2b2t.org"));
        assertTrue(out.contains("[15:02:11]"), "timestamp kept");
        assertTrue(out.contains("[ip removed]") && out.contains("[user]") && out.contains("[host removed]"));
    }

    @Test
    void nullBecomesEmpty() {
        assertEquals("", LogSanitizer.sanitize(null));
    }

    // ---- rules added from real Minecraft/server log samples ------------------------------------------

    @Test
    void redactsRealJoinLeaveAndLoginLines() {
        assertEquals("[player] joined the game", LogSanitizer.sanitize("x0mx joined the game"));
        assertEquals("[player] left the game", LogSanitizer.sanitize("x0mx left the game"));
        String out = LogSanitizer.sanitize("x0mx[/10.0.0.119:61136] logged in with entity id 89");
        assertFalse(out.contains("x0mx"), "username before [/addr] stripped");
        assertFalse(out.contains("10.0.0.119"), "ip:port stripped");
        assertTrue(out.contains("[player]") && out.contains("[ip removed]"));
    }

    @Test
    void redactsSettingUserDisconnectAndAdvancement() {
        assertEquals("Setting user: [player]", LogSanitizer.sanitize("Setting user: x0mx"));
        assertEquals("[player] lost connection: Disconnected",
            LogSanitizer.sanitize("x0mx lost connection: Disconnected"));
        assertEquals("[player] has made the advancement [Stone Age]",
            LogSanitizer.sanitize("x0mx has made the advancement [Stone Age]"));
    }

    @Test
    void redactsConnectTargetIncludingUncommonTld() {
        assertEquals("Connecting to [host removed], 25565", LogSanitizer.sanitize("Connecting to 2b2t.org, 25565"));
        // "Connecting to X" catches any target regardless of whether it's a common-TLD host, odd TLD, or IP
        assertEquals("Connecting to [host removed], 25565", LogSanitizer.sanitize("Connecting to my-box.lan, 25565"));
        assertEquals("Connecting to [host removed]", LogSanitizer.sanitize("Connecting to 51.83.128.9:25565"));
    }

    @Test
    void knownLocalUsernameRedactedEvenInFreeformCommandOutput() {
        // With the reporter's own username known, self-hosted command output mentioning it is scrubbed...
        assertEquals("Gave 1 [Diamond Sword] to [player]",
            LogSanitizer.sanitize("Gave 1 [Diamond Sword] to x0mx", "x0mx"));
        assertEquals("Teleported [player] to 0.5, 64.0, 0.5",
            LogSanitizer.sanitize("Teleported x0mx to 0.5, 64.0, 0.5", "x0mx"));
        // ...but WITHOUT a known username, freeform command output is a documented limitation.
        assertTrue(LogSanitizer.sanitize("Gave 1 [Diamond Sword] to x0mx").contains("x0mx"));
    }

    @Test
    void shortUsernameNotUsedForBlanketRedaction() {
        // a <3-char username is too collision-prone to blanket-redact; don't mangle the line
        assertEquals("hi ab cd", LogSanitizer.sanitize("hi ab cd", "ab"));
    }
}
