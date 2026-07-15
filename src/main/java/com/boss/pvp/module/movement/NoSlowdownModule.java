package com.boss.pvp.module.movement;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;

public final class NoSlowdownModule extends Module {
    public NoSlowdownModule() {
        super(BossPvpAddon.ID + ":noslowdown", "NoSlowdown", "Keep full movement speed while eating, drinking, or blocking.");
    }
}
