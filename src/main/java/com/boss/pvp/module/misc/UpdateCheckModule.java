package com.boss.pvp.module.misc;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;

/**
 * Opt-out control for the launch-time update check. When "Check for updates at launch" is on (the default),
 * the addon makes a single GitHub Releases API request at launch to see if a newer version exists and, if so,
 * shows a one-time chat notice and a small HUD indicator. It is <b>read-only</b>: nothing is ever downloaded
 * or installed. Turn the toggle off to disable the check entirely — then no GitHub request is made at all.
 */
public final class UpdateCheckModule extends Module {

    public UpdateCheckModule() {
        super(BossPvpAddon.ID + ":updatecheck", "Update Checker",
            "Checks once at game launch whether a newer version exists and tells you in chat and on the "
                + "HUD. Never downloads or installs anything. On by default; turn off to skip the check.");
        add(new BoolSetting("check", "Check for updates at launch", true)
            .description("Asks GitHub once at launch whether a newer version exists. Only looks — never "
                + "downloads or installs anything. Uncheck to skip the check entirely (nothing is sent)."));
    }

    /** Whether the launch-time update check is allowed by the user's toggle (default on). */
    public boolean checkEnabled() {
        return bool("check");
    }
}
