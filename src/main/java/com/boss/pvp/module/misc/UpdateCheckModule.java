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
            "Checks GitHub at launch for a newer release and notifies you (chat + HUD). Read-only — never "
                + "downloads or installs anything. On by default; turn off to make no GitHub request at all.");
        add(new BoolSetting("check", "Check for updates at launch", true)
            .description("Make a single GitHub Releases API request at launch to see if a newer version "
                + "exists. Read-only, no download. Uncheck to disable the check entirely (no request)."));
    }

    /** Whether the launch-time update check is allowed by the user's toggle (default on). */
    public boolean checkEnabled() {
        return bool("check");
    }
}
