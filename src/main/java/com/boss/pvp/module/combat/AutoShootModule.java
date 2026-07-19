package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismInventoryHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class AutoShootModule extends Module {

    private long lastThrowMs = 0L;
    private int prevSlot = -1;

    public AutoShootModule() {
        super(BossPvpAddon.ID + ":autoshoot", "AutoShoot", "Automatically throws snowballs, eggs, or pearls at the player you're looking at.");
        add(new BoolSetting("snowball", "Snowball", true).group("Projectiles"));
        add(new BoolSetting("egg", "Egg", false).group("Projectiles"));
        add(new BoolSetting("pearl", "Ender pearl", false).group("Projectiles"));
        add(new IntSetting("delay", "Delay (ms)", 500, 0, 3000, 10).group("General"));
        add(new BoolSetting("onlyVisible", "Only visible targets", true).group("General"));
        add(new BoolSetting("switchBack", "Switch back after", true).group("General"));
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && prevSlot >= 0 && bool("switchBack")) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        prevSlot = -1;
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null) return;

        if (!(mc.crosshairPickEntity instanceof Player target) || target == p) { restore(mc); return; }
        if (bool("onlyVisible") && !PvpUtil.canSeeEntity(mc, p, target)) { restore(mc); return; }
        if (System.currentTimeMillis() - lastThrowMs < integer("delay")) return;

        int slot = throwableSlot(p);
        if (slot < 0) { restore(mc); return; }

        if (p.getInventory().getSelectedSlot() != slot) {
            if (prevSlot < 0) prevSlot = p.getInventory().getSelectedSlot();
            AutismInventoryHelper.selectHotbarSlot(mc, slot);
            return; // throw next tick, once the slot switch has applied
        }

        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        lastThrowMs = System.currentTimeMillis();
        restore(mc);
    }

    private int throwableSlot(LocalPlayer p) {
        for (int i = 0; i <= 8; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (bool("snowball") && s.is(Items.SNOWBALL)) return i;
            if (bool("egg") && s.is(Items.EGG)) return i;
            if (bool("pearl") && s.is(Items.ENDER_PEARL)) return i;
        }
        return -1;
    }

    private void restore(Minecraft mc) {
        if (prevSlot >= 0 && bool("switchBack") && mc.player != null) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        prevSlot = -1;
    }
}
