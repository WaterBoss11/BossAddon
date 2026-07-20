package com.boss.utility.flag;

import com.boss.utility.BossUtilityAddon;

import java.util.List;

/**
 * Stable public bridge for cross-addon flag deduplication. When both boss-pvp and BossUtility are installed,
 * boss-pvp (the designated reporter) fires a single combined kick/crash report and reflectively calls this
 * {@code enabledModuleSummary()} to include BossUtility's modules in that one embed. BossUtility itself
 * suppresses its own report for the event (see {@link FlagReporter}).
 *
 * <p>Keep the class name ({@code com.boss.utility.flag.FlagBridge}), method name, and return type
 * ({@code List<String>} of "Name (Category)") stable across versions — boss-pvp binds to them by name at
 * runtime with no compile-time dependency.
 */
public final class FlagBridge {

    private FlagBridge() {}

    /** "Name (Category)" for each currently-enabled BossUtility module. Never throws; empty list if unavailable. */
    public static List<String> enabledModuleSummary() {
        try {
            return BossUtilityAddon.enabledModuleSummary();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * Whether BossUtility will actually emit a flag report right now (its "Crash & Kick Reports" toggle is on
     * and its channel is configured). Symmetric counterpart of boss-pvp's bridge method — boss-pvp is the
     * reporter today so it doesn't call this, but the contract is kept symmetric so the direction can flip
     * without a protocol change. Never throws; false on any uncertainty.
     */
    public static boolean isReportingEnabled() {
        try {
            return BossUtilityAddon.flagReport == null || BossUtilityAddon.flagReport.reportingEnabled();
        } catch (Throwable t) {
            return false;
        }
    }
}
