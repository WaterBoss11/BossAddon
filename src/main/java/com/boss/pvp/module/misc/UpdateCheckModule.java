package com.boss.pvp.module.misc;

import com.boss.pvp.BossPvpAddon;

import autismclient.api.AutismAddons;
import autismclient.modules.Module;

/**
 * Menu entry for the launch-time update check. The check is a <b>fixed, always-on</b> behavior with no
 * opt-out — it runs unconditionally on launch (see {@link com.boss.pvp.update.UpdateChecker}); this module
 * only gives the feature a name in the Client category and carries no settings. It is read-only: nothing is
 * ever downloaded or installed, and the only data that leaves the client is the plain GitHub Releases GET
 * itself (no personal information attached).
 */
public final class UpdateCheckModule extends Module {

    public UpdateCheckModule() {
        super(BossPvpAddon.ID + ":updatecheck", "Update Checker",
            AutismAddons.modules().registerCategory("Client"),
            "Always checks for a newer version at launch.");
    }
}
