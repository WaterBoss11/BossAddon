package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;
import autismclient.api.module.*;

public final class AntiEntityPushModule extends Module {

    public AntiEntityPushModule() {
        super(BossPvpAddon.ID + ":antientitypush", "AntiEntityPush", "Stops mobs and players from pushing you around.");
        add(new BoolSetting("onlyWhileSurrounded", "Only while Surround active", false)
            .description("Only stop pushes while the Surround module is turned on.").group("General"));
    }

    public boolean onlyWhileSurrounded() { return bool("onlyWhileSurrounded"); }
}
