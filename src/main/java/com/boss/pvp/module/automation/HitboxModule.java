package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;
import autismclient.api.module.*;

import com.boss.pvp.util.MenuMode;

public final class HitboxModule extends Module {

    public HitboxModule() {
        super(BossPvpAddon.ID + ":hitbox", "Hitbox", "Enlarges enemy hitboxes to hit easier.");

        add(new DoubleSetting("expand", "Extra width", 0.20, 0.0, 0.6, 0.01).group("General"));
        add(new DoubleSetting("expandV", "Extra height", 0.20, 0.0, 0.6, 0.01).group("General"));
        add(new BoolSetting("playersOnly", "Players only", true).group("General"));
        add(new BoolSetting("onlyTargeted", "Only your current target", false).group("General").visibleWhen(MenuMode::advanced));
    }

    public double expand() { return decimal("expand"); }
    public double expandV() { return decimal("expandV"); }
    public boolean playersOnly() { return bool("playersOnly"); }
    public boolean onlyTargeted() { return bool("onlyTargeted"); }
}
