package com.boss.utility.module.movement;

import com.boss.utility.BossUtilityAddon;

import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Sprint — automatically sprints. Studied and rewritten from Meteor Client's Sprint (GPL-3.0).
 *
 * <p>Each tick, sets the player sprinting when they are moving forward and able to (has food, not
 * sneaking / mobility-restricted). The combat extras from the original (unsprint-on-hit, keep-sprint)
 * are intentionally dropped — this is the plain QoL auto-sprint.
 */
public final class SprintModule extends Module {

    public SprintModule() {
        super(BossUtilityAddon.ID + ":sprint", "Sprint", "Automatically sprints when moving forward.");
        add(new BoolSetting("stationary", "Sprint while stationary", false)
            .description("Keep sprinting even when not pressing forward."));
        add(new BoolSetting("inWater", "Sprint in water", false));
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (!bool("inWater") && p.isInWater()) { return; }
        p.setSprinting(shouldSprint(mc, p));
    }

    private boolean shouldSprint(Minecraft mc, LocalPlayer p) {
        boolean movingForward = p.zza > 0.8f || (bool("stationary") && (p.zza != 0 || p.xxa != 0));
        if (!bool("stationary") && p.zza <= 0.8f) return false;
        if (p.isShiftKeyDown() || p.isCrouching()) return false;   // sneaking / crawling
        if (!p.getFoodData().hasEnoughFood()) return false;
        if (p.horizontalCollision && !p.minorHorizontalCollision) return false;
        return movingForward;
    }
}
