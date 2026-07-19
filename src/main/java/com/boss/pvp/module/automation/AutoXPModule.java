package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.input.HeldSlotManager;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismInventoryHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class AutoXPModule extends Module {

    private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

    private int thrown = 0;
    private long lastThrowMs = 0L;
    private long cooldownUntil = 0L;
    private int prevSlot = -1;

    public AutoXPModule() {
        super(BossPvpAddon.ID + ":autoxp", "AutoXP", "Throw XP bottles to mend damaged gear in a fight.");

        add(new IntSetting("threshold", "Mend below durability", 50, 1, 100, 1).formatter(v -> v + "%").group("General"));
        add(new IntSetting("burst", "Throws per burst", 3, 1, 16, 1).group("General"));
        add(new IntSetting("delay", "Throw delay (ms)", 150, 0, 1000, 10).group("General"));
        add(new IntSetting("cooldown", "Burst cooldown (ms)", 2000, 0, 10000, 100).group("General"));
        add(new BoolSetting("switchBack", "Switch back to previous item", true).group("General"));
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && prevSlot >= 0 && bool("switchBack")) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        thrown = 0;
        prevSlot = -1;
        HeldSlotManager.clear(this);
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || mc.gui.screen() != null) return;

        if (!HeldSlotManager.holds(this) && prevSlot >= 0) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            prevSlot = -1;
            return;
        }

        long now = System.currentTimeMillis();
        if (now < cooldownUntil) { HeldSlotManager.release(this); return; }
        if (!gearNeedsMend(p) || findXp(p) < 0) {
            thrown = 0;
            HeldSlotManager.release(this);
            restoreSlot(mc, p);
            return;
        }

        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_AUTOXP);
        if (!HeldSlotManager.holds(this)) return;

        if (now - lastThrowMs < PvpUtil.jitterMs(integer("delay"))) return;
        if (!ensureXp(mc, p)) return;

        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        p.swing(InteractionHand.MAIN_HAND);
        lastThrowMs = now;
        thrown++;

        if (thrown >= integer("burst")) {
            thrown = 0;
            cooldownUntil = now + integer("cooldown");
            restoreSlot(mc, p);
            HeldSlotManager.release(this);
        }
    }

    private boolean gearNeedsMend(LocalPlayer p) {
        if (damagedBelow(p.getMainHandItem()) || damagedBelow(p.getOffhandItem())) return true;
        for (EquipmentSlot s : ARMOR) if (damagedBelow(p.getItemBySlot(s))) return true;
        return false;
    }

    private boolean damagedBelow(ItemStack s) {
        if (s == null || s.isEmpty() || !s.isDamageableItem() || s.getMaxDamage() <= 0) return false;
        double remain = 100.0 * (1.0 - (double) s.getDamageValue() / s.getMaxDamage());
        return remain < integer("threshold");
    }

    private int findXp(LocalPlayer p) {
        for (int i = 0; i <= 8; i++) {
            if (p.getInventory().getItem(i).is(Items.EXPERIENCE_BOTTLE)) return i;
        }
        return -1;
    }

    private boolean ensureXp(Minecraft mc, LocalPlayer p) {
        if (p.getMainHandItem().is(Items.EXPERIENCE_BOTTLE)) return true;
        int slot = findXp(p);
        if (slot < 0) return false;
        if (prevSlot < 0) prevSlot = p.getInventory().getSelectedSlot();
        AutismInventoryHelper.selectHotbarSlot(mc, slot);
        return false;
    }

    private void restoreSlot(Minecraft mc, LocalPlayer p) {
        if (prevSlot >= 0 && bool("switchBack")) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        prevSlot = -1;
    }
}
