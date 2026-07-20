package com.boss.utility.module.player;

import com.boss.utility.BossUtilityAddon;

import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.Random;

/**
 * AntiAFK — performs small periodic actions to avoid AFK kicks. Studied and rewritten from Meteor
 * Client's AntiAFK (GPL-3.0). Jump / swing / sneak / spin, driven off the client tick — no packets
 * beyond what the vanilla actions produce.
 */
public final class AntiAfkModule extends Module {

    private final Random rng = new Random();
    private int sneakTimer = 0;

    public AntiAfkModule() {
        super(BossUtilityAddon.ID + ":anti-afk", "AntiAFK", "Performs small actions to avoid AFK kicks.");
        add(new BoolSetting("jump", "Jump", true));
        add(new BoolSetting("swing", "Swing hand", false));
        add(new BoolSetting("sneak", "Sneak", false));
        add(new BoolSetting("spin", "Spin", true));
        add(new IntSetting("spinSpeed", "Spin speed", 7, 1, 30, 1).group("Spin"));
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) mc.options.keyShift.setDown(false);
        sneakTimer = 0;
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.options == null) return;

        if (bool("jump")) {
            if (mc.options.keyJump.isDown()) mc.options.keyJump.setDown(false);
            else if (rng.nextInt(100) == 0) mc.options.keyJump.setDown(true);
        }
        if (bool("swing") && rng.nextInt(100) == 0) {
            p.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        }
        if (bool("sneak")) {
            if (sneakTimer++ >= 5) { mc.options.keyShift.setDown(false); if (rng.nextInt(100) == 0) sneakTimer = 0; }
            else mc.options.keyShift.setDown(true);
        }
        if (bool("spin")) {
            p.setYRot(p.getYRot() + integer("spinSpeed"));
            p.setYHeadRot(p.getYRot());
        }
    }
}
