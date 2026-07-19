package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;
import autismclient.api.module.*;

public final class HitboxModule extends Module {

    public HitboxModule() {
        super(BossPvpAddon.ID + ":hitbox", "Hitbox", "Makes enemies easier to hit by enlarging their hitboxes.");

        add(new DoubleSetting("expand", "Extra width", 0.20, 0.0, 0.6, 0.01).group("General"));
        add(new DoubleSetting("expandV", "Extra height", 0.20, 0.0, 0.6, 0.01).group("General"));
        add(new BoolSetting("playersOnly", "Players only", true).group("General"));
        add(new BoolSetting("onlyTargeted", "Only your current target", false).group("General"));
    }

    public double expand() { return decimal("expand"); }
    public double expandV() { return decimal("expandV"); }
    public boolean playersOnly() { return bool("playersOnly"); }
    public boolean onlyTargeted() { return bool("onlyTargeted"); }
}
