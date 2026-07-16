package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismInventoryHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.Enchantments;

public final class AutoWeaponModule extends Module {

    private int prevSlot = -1;
    private boolean switchedPending = false;

    public AutoWeaponModule() {
        super(BossPvpAddon.ID + ":autoweapon", "AutoWeapon", "Switch to the best weapon before each KillAura hit, then switch back.");
        add(new BoolSetting("switchBack", "Switch back after hit", true).group("General"));
        add(new BoolSetting("onlyHotbar", "Only hotbar slots", true)
            .description("Only consider hotbar slots 0-8 (off-hotbar switching would need a container swap — not supported).").group("General"));
    }

    @Override
    public void onDisable() {
        if (switchedPending && bool("switchBack") && prevSlot >= 0) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.player != null) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        switchedPending = false;
        prevSlot = -1;
    }

    public void selectBestWeapon(Entity target) {
        if (!isEnabled() || target == null) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;

        int cur = p.getInventory().getSelectedSlot();
        int best = cur;
        double bestScore = scoreWeapon(p.getInventory().getItem(cur), target, p);
        for (int i = 0; i <= 8; i++) {
            double sc = scoreWeapon(p.getInventory().getItem(i), target, p);
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        if (best != cur) {
            if (!switchedPending) prevSlot = cur;
            AutismInventoryHelper.selectHotbarSlot(mc, best);
            switchedPending = true;
        }
    }

    public void tick(Minecraft mc) {

        if (!switchedPending) return;
        if (bool("switchBack") && prevSlot >= 0 && mc.player != null) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        switchedPending = false;
        prevSlot = -1;
    }

    private double scoreWeapon(ItemStack stack, Entity target, LocalPlayer p) {
        if (stack == null || stack.isEmpty()) return 0.0;
        double score = attackDamage(stack);
        for (var e : stack.getEnchantments().entrySet()) {
            int lvl = e.getIntValue();
            if (lvl <= 0) continue;
            var h = e.getKey();
            if (h.is(Enchantments.SHARPNESS)) score += 0.5 * lvl;
            else if (h.is(Enchantments.SMITE) && isUndead(target)) score += 2.5 * lvl;
            else if (h.is(Enchantments.IMPALING) && target.isInWaterOrRain()) score += 2.5 * lvl;
        }

        if (stack.is(Items.MACE) && p.fallDistance > 1.5) score += Math.min(20.0, p.fallDistance);

        if (stack.getItem() instanceof AxeItem && target instanceof Player pl && pl.isBlocking()) score += 5.0;
        return score;
    }

    private double attackDamage(ItemStack stack) {
        ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (mods == null) return 1.0;

        return mods.compute(Attributes.ATTACK_DAMAGE, 1.0, EquipmentSlot.MAINHAND);
    }

    private boolean isUndead(Entity e) {
        return e instanceof Zombie || e instanceof AbstractSkeleton || e instanceof WitherBoss;
    }
}
