package com.boss.pvp.flag;

import com.boss.pvp.BossPvpAddon;

import java.util.List;

/**
 * Stable public bridge for cross-addon flag deduplication. When both boss-pvp and BossUtility are installed,
 * the designated reporter (boss-pvp) fires a single combined kick/crash report; to include the other addon's
 * modules it reflectively calls that addon's {@code FlagBridge.enabledModuleSummary()}.
 *
 * <p>This class is the deliberately-exposed contract each addon ships for that reflection: a
 * {@code public static List<String> enabledModuleSummary()} with a stable name/signature. Keep the class
 * name ({@code com.boss.pvp.flag.FlagBridge}), method name, and return type ({@code List<String>} of
 * "Name (Category)") stable across versions — the counterpart addon binds to them by name at runtime.
 *
 * <p>boss-pvp is the reporter, so it calls BossUtility's bridge rather than being called; this class is the
 * symmetric counterpart of that contract (kept so the direction can be flipped without a protocol change).
 */
public final class FlagBridge {

    private FlagBridge() {}

    /** "Name (Category)" for each currently-enabled boss-pvp module. Never throws; empty list if unavailable. */
    public static List<String> enabledModuleSummary() {
        try {
            return BossPvpAddon.enabledModuleSummary();
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * Whether boss-pvp's "Crash & Kick Reports" toggle is on. When both addons are installed, BossUtility
     * calls this to decide whether to stay silent (boss-pvp is reporting the combined embed) or fall back to
     * reporting independently (boss-pvp's toggle is off). Never throws; on any uncertainty returns
     * {@code false} so BossUtility reports rather than both going silent.
     */
    public static boolean isReportingEnabled() {
        try {
            return BossPvpAddon.flagReport == null || BossPvpAddon.flagReport.reportingEnabled();
        } catch (Throwable t) {
            return false;
        }
    }
}
