package com.boss.utility.module.movement;

import com.boss.utility.BossUtilityAddon;

import autismclient.modules.Module;
import autismclient.api.module.DoubleSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

/**
 * Step — walk up full blocks instantly. Studied and rewritten from Meteor Client's Step (GPL-3.0).
 *
 * <p>Raises the player's {@code STEP_HEIGHT} attribute while active and restores it on disable. The
 * default of 1.0 (up to ~1.25) is generally server-accepted; higher values are anti-cheat-detectable,
 * so this is a plain movement QoL. The original's crystal-aware "safe step" is dropped (combat).
 */
public final class StepModule extends Module {

    private static final double VANILLA_STEP = 0.6;
    private double prevStep = VANILLA_STEP;

    public StepModule() {
        super(BossUtilityAddon.ID + ":step", "Step", "Walk up full blocks instantly.");
        add(new DoubleSetting("height", "Step height", 1.0, 0.5, 2.0, 0.05)
            .description("How tall a step you can walk up. 1.0–1.25 is the vanilla-legit range."));
    }

    @Override
    public void onDisable() {
        AttributeInstance a = step(Minecraft.getInstance());
        if (a != null) a.setBaseValue(prevStep);
    }

    public void tick(Minecraft mc) {
        AttributeInstance a = step(mc);
        if (a == null) return;
        double want = decimal("height");
        // capture the real value once (before we start overriding it)
        if (a.getBaseValue() != want && a.getBaseValue() != VANILLA_STEP) prevStep = a.getBaseValue();
        if (a.getBaseValue() != want) a.setBaseValue(want);
    }

    private AttributeInstance step(Minecraft mc) {
        LocalPlayer p = mc.player;
        return p == null ? null : p.getAttribute(Attributes.STEP_HEIGHT);
    }
}
