package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.MenuMode;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismInventoryHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class AutoGapModule extends Module {

    private static final Item[] FOODS = {
        Items.COOKED_BEEF, Items.COOKED_PORKCHOP, Items.COOKED_CHICKEN, Items.COOKED_MUTTON,
        Items.COOKED_COD, Items.COOKED_SALMON, Items.COOKED_RABBIT, Items.BAKED_POTATO,
        Items.BREAD, Items.GOLDEN_CARROT
    };

    private boolean eating = false;
    private int prevSlot = -1;
    private boolean switched = false;

    public AutoGapModule() {
        super(BossPvpAddon.ID + ":autogap", "AutoGap", "Eats gapples or food when low.");

        add(new DoubleSetting("health", "Eat below HP", 14.0, 1.0, 19.5, 0.5).group("General"));
        add(new IntSetting("hunger", "Eat below hunger", 16, 0, 20, 1).group("General"));
        add(new BoolSetting("preferGapInCombat", "Prefer gapple in combat", true).group("General").visibleWhen(MenuMode::advanced));
        add(new BoolSetting("eatNormalFood", "Eat normal food too", true).group("General"));
        add(new BoolSetting("pauseOnMove", "Pause while moving", false).group("General").visibleWhen(MenuMode::advanced));
        add(new BoolSetting("reEquip", "Switch back to previous item", true).group("General").visibleWhen(MenuMode::advanced));
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.options != null) mc.options.keyUse.setDown(false);
        stopState(mc);
        HeldSlotManager.clear(this);
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.options == null) { stopState(mc); return; }

        if (!HeldSlotManager.holds(this) && eating) {
            mc.options.keyUse.setDown(false);
            if (switched && prevSlot >= 0) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            stopState(mc);
            return;
        }

        if (eating) {
            HeldSlotManager.request(this, HeldSlotManager.PRIORITY_AUTOGAP);
            boolean hasFood = isEatable(p.getMainHandItem());
            if (!need(p) || !hasFood || mc.gui.screen() != null) { stop(mc, p); return; }
            if (bool("pauseOnMove") && moving(p)) { mc.options.keyUse.setDown(false); return; }
            mc.options.keyUse.setDown(true);
            com.boss.pvp.util.CombatManager.pauseCombat(3);
            return;
        }

        if (!need(p) || mc.gui.screen() != null) { HeldSlotManager.release(this); return; }
        if (bool("pauseOnMove") && moving(p)) { HeldSlotManager.release(this); return; }

        int slot = chooseFoodSlot(p);
        if (slot < 0) { HeldSlotManager.release(this); return; }

        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_AUTOGAP);
        if (!HeldSlotManager.holds(this)) return;

        prevSlot = p.getInventory().getSelectedSlot();
        if (slot != prevSlot) {
            AutismInventoryHelper.selectHotbarSlot(mc, slot);
            switched = true;
        }
        eating = true;
        com.boss.pvp.util.CombatManager.pauseCombat(3);
    }

    private boolean need(LocalPlayer p) {
        return p.getHealth() <= (float) decimal("health")
            || p.getFoodData().getFoodLevel() <= integer("hunger");
    }

    private boolean moving(LocalPlayer p) {
        return p.getDeltaMovement().horizontalDistanceSqr() > 0.0009;
    }

    private int chooseFoodSlot(LocalPlayer p) {
        boolean wantGap = (bool("preferGapInCombat") && inCombat(p)) || p.getHealth() <= (float) decimal("health");
        if (wantGap) {
            int gap = findSlot(p, Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE);
            if (gap >= 0) return gap;
            int food = bool("eatNormalFood") ? findFoodSlot(p) : -1;
            return food;
        }

        int food = bool("eatNormalFood") ? findFoodSlot(p) : -1;
        if (food >= 0) return food;
        return findSlot(p, Items.ENCHANTED_GOLDEN_APPLE, Items.GOLDEN_APPLE);
    }

    private int findSlot(LocalPlayer p, Item... items) {
        for (int i = 0; i <= 8; i++) {
            ItemStack s = p.getInventory().getItem(i);
            for (Item it : items) if (s.is(it)) return i;
        }
        return -1;
    }

    private int findFoodSlot(LocalPlayer p) {
        for (int i = 0; i <= 8; i++) {
            if (isFood(p.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private boolean isEatable(ItemStack s) {
        if (s.is(Items.ENCHANTED_GOLDEN_APPLE) || s.is(Items.GOLDEN_APPLE)) return true;
        return bool("eatNormalFood") && isFood(s);
    }

    private boolean isFood(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        for (Item it : FOODS) if (s.is(it)) return true;
        return false;
    }

    private boolean inCombat(LocalPlayer p) {
        if (p.hurtTime > 0) return true;
        if (BossPvpAddon.killAura != null && BossPvpAddon.killAura.isEnabled()
            && BossPvpAddon.killAura.currentTarget() != null) return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return false;
        for (Player pl : mc.level.players()) {
            if (pl == p || pl.isSpectator()) continue;
            if (pl.distanceToSqr(p) <= 64.0) return true;
        }
        return false;
    }

    private void stop(Minecraft mc, LocalPlayer p) {
        mc.options.keyUse.setDown(false);
        if (switched && bool("reEquip") && prevSlot >= 0) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        stopState(mc);
    }

    private void stopState(Minecraft mc) {

        if (mc != null && mc.options != null) mc.options.keyUse.setDown(false);
        eating = false;
        switched = false;
        prevSlot = -1;
        HeldSlotManager.release(this);
    }
}
