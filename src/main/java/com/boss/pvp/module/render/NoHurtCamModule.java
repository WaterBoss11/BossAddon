package com.boss.pvp.module.render;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;

public final class NoHurtCamModule extends Module {
    public NoHurtCamModule() {
        super(BossPvpAddon.ID + ":nohurtcam", "No Hurt Camera", "Removes the camera shake when you take damage.");
    }
}
