package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;
import autismclient.api.module.*;

public final class AntiEntityPushModule extends Module {

    public AntiEntityPushModule() {
        super(BossPvpAddon.ID + ":antientitypush", "AntiEntityPush", "Prevent other entities from pushing you out of position.");
        add(new BoolSetting("onlyWhileSurrounded", "Only while Surround active", false)
            .description("Only block collision pushes while the Surround module is enabled.").group("General"));
    }

    public boolean onlyWhileSurrounded() { return bool("onlyWhileSurrounded"); }
}
