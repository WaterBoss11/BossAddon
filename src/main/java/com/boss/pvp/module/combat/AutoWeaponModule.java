package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.AttackCharge;

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
        super(BossPvpAddon.ID + ":autoweapon", "AutoWeapon", "Switches to the best weapon before hitting.");
        add(new BoolSetting("switchBack", "Switch back after hit", true).group("General"));
        add(new BoolSetting("onlyHotbar", "Only hotbar slots", true)
            .description("Only pick weapons from your hotbar (switching from the rest of the inventory is not supported).").group("General"));
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
        int best = bestWeaponSlot(target, p);
        if (best != cur) {
            if (!switchedPending) prevSlot = cur;
            AutismInventoryHelper.selectHotbarSlot(mc, best);
            switchedPending = true;
        }
    }

    /**
     * Hotbar slot (0-8) of the highest-scoring weapon for {@code target}, defaulting to the currently selected
     * slot on a tie. This is the exact weapon {@link #selectBestWeapon} would swap to, shared so the charge gate
     * ({@link #chargeReadyForBestWeapon}) reasons about the same choice.
     */
    private int bestWeaponSlot(Entity target, LocalPlayer p) {
        int cur = p.getInventory().getSelectedSlot();
        int best = cur;
        double bestScore = scoreWeapon(p.getInventory().getItem(cur), target, p);
        for (int i = 0; i <= 8; i++) {
            double sc = scoreWeapon(p.getInventory().getItem(i), target, p);
            if (sc > bestScore) { bestScore = sc; best = i; }
        }
        return best;
    }

    /**
     * Is the weapon this module would swap to for {@code target} fully charged right now?
     *
     * <p>KillAura's full-charge gate historically read {@code getAttackStrengthScale} of the <i>currently held</i>
     * item, then swapped weapons on the same tick — so swapping to a slower weapon (e.g. sword -&gt; axe) let an
     * under-charged swing through. This computes the gate against the weapon {@link #selectBestWeapon} will
     * actually pick, using vanilla's shared attack-strength ticker (recovered from the current item's charge
     * scale). See {@link AttackCharge}.
     *
     * <p>Falls back to the currently held item's charge when this module is disabled or a target is unavailable.
     */
    public boolean chargeReadyForBestWeapon(Entity target) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc == null ? null : mc.player;
        if (p == null) return false;

        double currentScale = p.getAttackStrengthScale(0.0f);
        if (!isEnabled() || target == null) return currentScale >= 1.0;

        double currentDelay = AttackCharge.attackSpeedToDelay(p.getAttributeValue(Attributes.ATTACK_SPEED));

        ItemStack bestStack = p.getInventory().getItem(bestWeaponSlot(target, p));
        double bestDelay = AttackCharge.attackSpeedToDelay(weaponAttackSpeed(bestStack, p));

        return AttackCharge.bestWeaponCharged(currentScale, currentDelay, bestDelay);
    }

    /**
     * ATTACK_SPEED attribute value the player would have holding {@code stack} in the main hand, mirroring
     * {@link #attackDamage} but for attack speed. Bare hand / no modifiers falls back to the player's base speed.
     */
    private double weaponAttackSpeed(ItemStack stack, LocalPlayer p) {
        double base = p.getAttributeBaseValue(Attributes.ATTACK_SPEED);
        if (stack == null || stack.isEmpty()) return base;
        ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
        if (mods == null) return base;
        return mods.compute(Attributes.ATTACK_SPEED, base, EquipmentSlot.MAINHAND);
    }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
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
