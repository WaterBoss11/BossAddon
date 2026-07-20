package com.boss.utility.flag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests BossUtility's Part-1 cross-addon fallback: the reflective read of the reporter addon's
 * {@code FlagBridge.isReportingEnabled()} ({@link FlagReporter#bridgeReportingEnabled}) and the
 * suppress-vs-report decision. In production it reads boss-pvp's bridge; here it exercises the same mechanism
 * against BossUtility's own bridge and stand-in classes (boss-pvp isn't on the test classpath, matching the
 * standalone build), which is exactly what makes the fallback safe when the two versions don't match.
 */
class FlagBridgeReadTest {

    public static final class TrueBridge { public static boolean isReportingEnabled() { return true; } }
    public static final class FalseBridge { public static boolean isReportingEnabled() { return false; } }
    public static final class WrongMethodBridge { public static boolean somethingElse() { return true; } }
    public static final class ThrowingBridge {
        public static boolean isReportingEnabled() { throw new IllegalStateException("boom"); }
    }

    private static String n(Class<?> c) { return c.getName(); }

    @Test
    void readsTrueAndFalseBridges() {
        assertEquals(Boolean.TRUE, FlagReporter.bridgeReportingEnabled(n(TrueBridge.class)));
        assertEquals(Boolean.FALSE, FlagReporter.bridgeReportingEnabled(n(FalseBridge.class)));
    }

    @Test
    void missingWrongOrThrowingBridgeReturnsNull() {
        assertNull(FlagReporter.bridgeReportingEnabled("com.boss.nonexistent.FlagBridge"));
        assertNull(FlagReporter.bridgeReportingEnabled(n(WrongMethodBridge.class)));
        assertNull(FlagReporter.bridgeReportingEnabled(n(ThrowingBridge.class)));
    }

    @Test
    void suppressDecisionOnlyWhenReporterConfirmsTrue() {
        // BossUtility suppresses iff Boolean.TRUE.equals(bridgeResult); else it reports independently.
        assertTrue(Boolean.TRUE.equals(FlagReporter.bridgeReportingEnabled(n(TrueBridge.class))));    // suppress
        assertFalse(Boolean.TRUE.equals(FlagReporter.bridgeReportingEnabled(n(FalseBridge.class))));  // report: toggle off
        assertFalse(Boolean.TRUE.equals(FlagReporter.bridgeReportingEnabled("com.boss.gone")));       // report: no bridge
        assertFalse(Boolean.TRUE.equals(FlagReporter.bridgeReportingEnabled(n(ThrowingBridge.class))));// report: bridge threw
    }

    @Test
    void ownBridgeResolvesAndReportsEnabledByDefault() {
        // The real (symmetric) BossUtility bridge resolves reflectively and reads true by default (no module
        // registered in unit-test state), and its module summary is a non-null list.
        assertEquals(Boolean.TRUE, FlagReporter.bridgeReportingEnabled("com.boss.utility.flag.FlagBridge"));
        assertTrue(FlagBridge.isReportingEnabled());
        assertNotNull(FlagBridge.enabledModuleSummary());
    }
}
