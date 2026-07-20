package com.boss.utility.module.misc;

import com.boss.utility.BossUtilityAddon;

import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;

/**
 * Opt-out control for the flag reporter. When "Report crashes &amp; kicks" is on (the default), the client
 * reports kicks and crashes — the event, its reason, and which BossUtility modules were enabled at the time —
 * to the developer's shared Discord channel. <b>No server name or IP is ever collected.</b> Turn the toggle
 * off to opt out entirely.
 */
public final class FlagReportModule extends Module {

    public FlagReportModule() {
        super(BossUtilityAddon.ID + ":flagreport", "Crash & Kick Reports",
            "Reports kicks and crashes (event, reason, your Minecraft username, and the enabled BossUtility "
                + "modules — never a server name or IP) to the developer's Discord. On by default; turn off "
                + "to opt out.");
        add(new BoolSetting("report", "Report crashes & kicks", true)
            .description("Send a kick/crash report — event, reason, your Minecraft username, and the "
                + "BossUtility modules enabled at the time (never a server name or IP) — to the dev channel. "
                + "Uncheck to opt out."));
    }

    /** Whether reporting is currently allowed by the user's toggle (default on). */
    public boolean reportingEnabled() {
        return bool("report");
    }
}
