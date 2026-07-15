package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;
import autismclient.api.module.*;

public final class HitboxModule extends Module {

    public HitboxModule() {
        super(BossPvpAddon.ID + ":hitbox", "Hitbox", "Inflates enemy hitboxes so hits land easier.");

        add(new DoubleSetting("expand", "Horizontal expand", 0.20, 0.0, 0.6, 0.01).group("General"));
        add(new DoubleSetting("expandV", "Vertical expand", 0.20, 0.0, 0.6, 0.01).group("General"));
        add(new BoolSetting("playersOnly", "Players only", true).group("General"));
        add(new BoolSetting("onlyTargeted", "Only the combat target", false).group("General"));
    }

    public double expand() { return decimal("expand"); }
    public double expandV() { return decimal("expandV"); }
    public boolean playersOnly() { return bool("playersOnly"); }
    public boolean onlyTargeted() { return bool("onlyTargeted"); }
}
