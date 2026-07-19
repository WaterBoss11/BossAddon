package com.boss.pvp.module.misc;

import com.boss.pvp.BossPvpAddon;

import autismclient.api.AutismAddons;
import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;

/**
 * Opt-out control for the flag reporter. When "Report crashes &amp; kicks" is on (the default), the client
 * reports kicks and crashes — the event, its reason, and which boss-pvp modules were enabled at the time —
 * to the developer's shared Discord channel. <b>No server name or IP is ever collected.</b> Turn the toggle
 * off to opt out entirely.
 */
public final class FlagReportModule extends Module {

    public FlagReportModule() {
        super(BossPvpAddon.ID + ":flagreport", "Crash & Kick Reports",
            AutismAddons.modules().registerCategory("Client"),
            "Report crashes and kicks to the developer.");
        add(new BoolSetting("report", "Report crashes & kicks", true)
            .description("Sends a report to the developer's Discord when you crash or get kicked: what "
                + "happened, why, your Minecraft username, and which boss-pvp modules were on. Never the "
                + "server's name or IP. Uncheck to opt out."));
    }

    /** Whether reporting is currently allowed by the user's toggle (default on). */
    public boolean reportingEnabled() {
        return bool("report");
    }
}
