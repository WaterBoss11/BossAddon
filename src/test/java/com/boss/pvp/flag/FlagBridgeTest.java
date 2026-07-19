package com.boss.pvp.flag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the reflective cross-addon bridge puller ({@link FlagReporter#pullModules}). It must return the
 * other addon's module list when the bridge is present and well-formed, and fall back to {@code null} (never
 * throw) on any mismatch — so a combined report still fires with just this addon's data.
 */
class FlagBridgeTest {

    // Stand-ins for another addon's FlagBridge, resolved by fully-qualified name like the real one.
    public static final class GoodBridge {
        public static List<String> enabledModuleSummary() { return List.of("Sprint (MOVEMENT)", "AntiAfk (PLAYER)"); }
    }
    public static final class WrongMethodBridge {
        public static List<String> notTheBridgeMethod() { return List.of("x"); }
    }
    public static final class NonListBridge {
        public static String enabledModuleSummary() { return "not a list"; }
    }
    public static final class ThrowingBridge {
        public static List<String> enabledModuleSummary() { throw new IllegalStateException("boom"); }
    }
    public static final class ThrowingBoolBridge {
        public static boolean isReportingEnabled() { throw new IllegalStateException("boom"); }
    }

    private static String name(Class<?> c) { return c.getName(); }

    // Local mirror of BossUtility.FlagReporter.bridgeReportingEnabled — reads a boolean bridge method by
    // name exactly like the real cross-addon Part-1 call, so we can exercise the fallback decision here.
    private static Boolean reflectBool(String className) {
        try {
            Class<?> c = Class.forName(className);
            Object res = c.getMethod("isReportingEnabled").invoke(null);
            return res instanceof Boolean b ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    @Test
    void pullsModulesFromAWellFormedBridge() {
        List<String> got = FlagReporter.pullModules(name(GoodBridge.class));
        assertEquals(List.of("Sprint (MOVEMENT)", "AntiAfk (PLAYER)"), got);
    }

    @Test
    void missingClassReturnsNull() {
        assertNull(FlagReporter.pullModules("com.boss.nonexistent.FlagBridge"));
    }

    @Test
    void wrongMethodReturnsNull() {
        assertNull(FlagReporter.pullModules(name(WrongMethodBridge.class)));
    }

    @Test
    void nonListReturnValueReturnsNull() {
        assertNull(FlagReporter.pullModules(name(NonListBridge.class)));
    }

    @Test
    void throwingBridgeReturnsNullNotException() {
        assertNull(FlagReporter.pullModules(name(ThrowingBridge.class)));
    }

    @Test
    void realBossPvpBridgeResolvesToNonNullList() {
        // The actual shipped bridge — with no world loaded the module registry is empty, but it must still
        // resolve reflectively and return a (possibly empty) non-null list, never throw.
        List<String> got = FlagReporter.pullModules("com.boss.pvp.flag.FlagBridge");
        assertNotNull(got);
        assertTrue(got.isEmpty() || got.stream().allMatch(s -> s.contains("(")));
    }

    // ---- Part 1: reporter-toggle bridge (BossUtility's suppress-vs-fallback decision) -----------------

    @Test
    void reporterToggleReadsTrueByDefault() {
        // With no module registered (unit-test state) boss-pvp's toggle is treated as on -> "reporting".
        assertTrue(FlagBridge.isReportingEnabled());
    }

    @Test
    void reporterToggleIsReflectivelyReadableFromTheRealBridge() {
        // Exactly how BossUtility reads it. A non-null TRUE means BossUtility suppresses (boss-pvp reports).
        Boolean got = reflectBool("com.boss.pvp.flag.FlagBridge");
        assertNotNull(got);
        assertTrue(got);
        assertTrue(Boolean.TRUE.equals(got));   // suppress decision -> suppressed
    }

    @Test
    void bridgeReadFallsBackToNullThenReports() {
        // Missing class / wrong method / throwing method all yield null -> caller must NOT suppress, i.e.
        // BossUtility falls back to reporting independently (Boolean.TRUE.equals(null) == false).
        assertNull(reflectBool("com.boss.nonexistent.FlagBridge"));
        assertNull(reflectBool(name(WrongMethodBridge.class)));
        assertNull(reflectBool(name(ThrowingBoolBridge.class)));
        assertFalse(Boolean.TRUE.equals(reflectBool("com.boss.nonexistent.FlagBridge")));
        assertFalse(Boolean.TRUE.equals(reflectBool(name(ThrowingBoolBridge.class))));
    }
}
