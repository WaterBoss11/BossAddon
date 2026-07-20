package com.boss.utility.module.player;

import com.boss.utility.BossUtilityAddon;

import autismclient.modules.Module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * AutoRespawn — instantly respawns after death. Studied and rewritten from Meteor Client's
 * AutoRespawn (GPL-3.0). Detects death on the client tick and sends the respawn command once per
 * death (the original hooks the death-screen open event; we key off health, which needs no mixin).
 */
public final class AutoRespawnModule extends Module {

    private boolean respawned = false;

    public AutoRespawnModule() {
        super(BossUtilityAddon.ID + ":auto-respawn", "AutoRespawn", "Instantly respawns after death.");
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (p.isDeadOrDying() || p.getHealth() <= 0.0f) {
            if (!respawned) { p.respawn(); respawned = true; }
        } else {
            respawned = false;
        }
    }
}
