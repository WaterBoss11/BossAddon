package com.boss.pvp.module.render;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;

public final class NoHurtCamModule extends Module {
    public NoHurtCamModule() {
        super(BossPvpAddon.ID + ":nohurtcam", "NoHurtCam", "Remove the camera shake/tilt when you take damage.");
    }
}
