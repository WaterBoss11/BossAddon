/*
 * Weapon-swap references: Krispy MaceSwapModule (originally All Rights Reserved;
 * user supplied written permission for GPL modification/redistribution), and Silk AutoMace (GPL-3.0).
 * Attribution/authorization is retained here; central third-party notices contain the license resources.
 */
package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.CombatManager;
import com.boss.pvp.util.InventoryUtil;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.PvpUtil;

import autismclient.modules.Module;
import autismclient.api.module.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.component.Weapon;
import net.minecraft.world.item.enchantment.ConditionalEffect;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentValueEffect;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.function.DoubleUnaryOperator;

public final class AutoWeaponModule extends Module implements HeldSlotManager.Revocable {

    private int prevSlot = -1;
    private int selectedSlot = -1;
    private int inventorySlot = -1;
    private LocalPlayer swappedPlayer;
    private Level swappedLevel;
    private ItemStack originalStack = ItemStack.EMPTY;
    private ItemStack borrowedStack = ItemStack.EMPTY;
    private Entity preparedTarget;
    private int preparedTick;
    private static final long MAX_PENDING_NANOS = 3_000_000_000L;
    private Entity pendingTarget;
    private long requestedAt;
    private long settledAt;
    private boolean pendingRaw;
    private boolean needsReplay;
    private boolean attackSent;
    private boolean requestAimed;
    private float requestYaw;
    private float requestPitch;

    public AutoWeaponModule() {
        super(BossPvpAddon.ID + ":autoweapon", "AutoWeapon", "Switches to the best weapon before hitting.");
        add(new BoolSetting("switchBack", "Switch back after hit", true).group("General"));
        add(new BoolSetting("onlyHotbar", "Only hotbar slots", true)
            .description("When disabled, also borrow weapons from the main inventory while its cursor is empty.").group("General"));
        add(new IntSetting("settleMs", "Equipment safety margin (ms)", 100, 50, 500, 25)
            .description("Waits for native equipment attributes and cooldown before replaying one attack. Cancels after three seconds.")
            .group("General"));
    }

    @Override public void onLeaseRevoked() { finish(Minecraft.getInstance()); }

    @Override
    public void onDisable() {
        finish(Minecraft.getInstance());
    }

    @Override
    public void onGameLeft() {
        discard();
    }

    /** False cancels the current attempt; a necessary swap retains at most one bounded attack intent. */
    public boolean selectBestWeapon(Entity target) {
        return selectBestWeapon(target, false);
    }

    public boolean selectBestWeapon(Entity target, boolean rawPacket) {
        if (!isEnabled()) return true;
        Minecraft mc = Minecraft.getInstance();
        if (!usable(mc, target)) return false;
        if (pendingTarget == null) {
            pendingTarget = target;
            swappedPlayer = mc.player;
            swappedLevel = mc.level;
            requestedAt = System.nanoTime();
            requestAimed = mc.crosshairPickEntity == target;
            requestYaw = mc.player.getYRot();
            requestPitch = mc.player.getXRot();
        }
        if (pendingTarget != target || attackSent) return false;
        pendingRaw |= rawPacket;
        if (!pendingValid(mc)) { finish(mc); return false; }
        boolean ready = prepare(mc);
        if (ready) {
            preparedTarget = target;
            preparedTick = mc.player.tickCount;
        } else if (pendingTarget != null) {
            needsReplay = true;
        }
        return ready;
    }

    private boolean prepare(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (prevSlot < 0) {
            if (!HeldSlotManager.tryAcquire(this, HeldSlotManager.PRIORITY_AUTOWEAPON)) return false;
            int best = bestWeaponSlot(pendingTarget, player);
            ItemStack chosen = player.getInventory().getItem(best);
            boolean smash = chosen.getItem() instanceof MaceItem && MaceItem.canSmashAttack(player);
            if (smash && !charged(player, chosen)) { finish(mc); return false; }
            boolean alreadyHeld = best == player.getInventory().getSelectedSlot() && attributesMatch(player, chosen);
            boolean alreadyCharged = charged(player, chosen);
            prevSlot = player.getInventory().getSelectedSlot();
            selectedSlot = best < 9 ? best : prevSlot;
            inventorySlot = best < 9 ? -1 : best;
            originalStack = player.getMainHandItem().copy();
            borrowedStack = chosen.copy();
            boolean selected = false;
            try {
                if (inventorySlot >= 0) {
                    if (!playerInventoryOpen(player) || !InventoryUtil.swapHandlerSlots(mc, inventorySlot, selectedSlot + 36)) return false;
                } else {
                    InventoryUtil.selectHotbarSlot(mc, selectedSlot);
                }
                selected = ownsSelection(mc);
                if (!selected) return false;
                // Native falling-mace bonus is immediate; do not miss its short collision window waiting for base attributes.
                if (smash || alreadyHeld && alreadyCharged) return true;
            } finally {
                if (!selected) finish(mc);
            }
        }
        if (!ownsSelection(mc)) { finish(mc); return false; }
        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_AUTOWEAPON);
        if (!attributesMatch(player, borrowedStack)) { settledAt = 0L; return false; }
        long now = System.nanoTime();
        if (settledAt == 0L) settledAt = now;
        var info = mc.getConnection() == null ? null : mc.getConnection().getPlayerInfo(player.getUUID());
        int latency = info == null ? 0 : info.getLatency();
        long wait = Decisions.settleNanos(weaponDelay(player, borrowedStack),
            mc.level.tickRateManager().nanosecondsPerTick(), latency, integer("settleMs"));
        boolean elapsed = now - settledAt >= wait && now - settledAt >= 0L;
        // Raw native auras may reset the client ticker even when their packet was cancelled; never alter that ticker.
        return elapsed && (pendingRaw || charged(player, borrowedStack));
    }

    private boolean pendingValid(Minecraft mc) {
        if (!usable(mc, pendingTarget) || mc.player != swappedPlayer || mc.level != swappedLevel
                || !Decisions.pendingAge(System.nanoTime() - requestedAt)) return false;
        if (!mc.player.isWithinAttackRange(borrowedStack.isEmpty() ? mc.player.getMainHandItem() : borrowedStack,
                pendingTarget.getBoundingBox(), 0.0) || !PvpUtil.canSeeEntity(mc, mc.player, pendingTarget)) return false;
        if (!PvpUtil.hostEnabled("kill-aura")) {
            if (requestAimed && mc.crosshairPickEntity != pendingTarget) return false;
            if (!requestAimed && (Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - requestYaw)) > 15.0f
                    || Math.abs(mc.player.getXRot() - requestPitch) > 15.0f)) return false;
        }
        return prevSlot < 0 || ownsSelection(mc);
    }

    private boolean ownsSelection(Minecraft mc) {
        return mc != null && mc.player == swappedPlayer && mc.level == swappedLevel && mc.player != null
            && HeldSlotManager.holds(this) && mc.player.getInventory().getSelectedSlot() == selectedSlot
            && ItemStack.isSameItemSameComponents(mc.player.getMainHandItem(), borrowedStack);
    }

    private static boolean attributesMatch(LocalPlayer player, ItemStack stack) {
        // Attack damage is not synchronized to the local client; the server-backed speed is the equipment acknowledgement.
        return Decisions.attributeMatches(player.getAttributeValue(Attributes.ATTACK_SPEED), weaponAttribute(stack, player, Attributes.ATTACK_SPEED));
    }

    public void attackAccepted(Entity target) {
        if (pendingTarget == target) attackSent = true;
    }

    @Override public String info() { return pendingTarget != null && needsReplay && !attackSent ? "Preparing" : ""; }

    public boolean consumePreparedAttack(Entity target) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc == null ? null : mc.player;
        boolean ready = player != null && preparedTarget == target && player == swappedPlayer
            && mc.level == swappedLevel && HeldSlotManager.holds(this)
            && player.tickCount == preparedTick && player.getInventory().getSelectedSlot() == selectedSlot
            && ItemStack.isSameItemSameComponents(player.getMainHandItem(), borrowedStack);
        preparedTarget = null;
        return ready;
    }

    public boolean chargeReadyForBestWeapon(Entity target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return false;
        if (!isEnabled()) return mc.player.getAttackStrengthScale(0.0f) >= 1.0f;
        return usable(mc, target) && charged(mc.player, mc.player.getInventory().getItem(bestWeaponSlot(target, mc.player)));
    }

    private boolean usable(Minecraft mc, Entity target) {
        return mc != null && mc.player != null && mc.level != null && mc.gameMode != null
            && mc.gui.screen() == null && !mc.player.isSpectator() && !mc.player.isUsingItem()
            && !CombatManager.isCombatPaused() && !PvpUtil.hostEnabled("hide") && target != null && target != mc.player
            && target.level() == mc.level && target.isAlive() && !target.isRemoved()
            && (!(target instanceof Player player) || !PvpUtil.isProtectedPlayer(mc, player));
    }

    private int bestWeaponSlot(Entity target, LocalPlayer p) {
        int count = bool("onlyHotbar") || !playerInventoryOpen(p) ? 9 : 36;
        double[] damage = new double[count];
        boolean[] disablesShield = new boolean[count];
        for (int i = 0; i < count; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            damage[i] = scoreWeapon(stack, target, p);
            Weapon weapon = stack.get(DataComponents.WEAPON);
            disablesShield[i] = weapon != null && weapon.disableBlockingForSeconds() > 0.0f
                && target instanceof LivingEntity living && living.isBlocking()
                && living.getViewVector(1.0f).dot(p.position().subtract(living.position()).normalize()) > 0.0;
        }
        return Decisions.bestSlot(damage, disablesShield, p.getInventory().getSelectedSlot());
    }

    private boolean charged(LocalPlayer p, ItemStack stack) {
        return Decisions.charged(p.getCurrentItemAttackStrengthDelay(), weaponDelay(p, stack),
            offset -> p.getAttackStrengthScale((float) offset));
    }

    private static double weaponDelay(LocalPlayer player, ItemStack stack) {
        double speed = weaponAttribute(stack, player, Attributes.ATTACK_SPEED);
        double delay = speed > 0.0 ? 20.0 / speed : Double.POSITIVE_INFINITY;
        return delay * Math.max(1.0f, stack.getOrDefault(DataComponents.MINIMUM_ATTACK_CHARGE, 0.0f));
    }

    static double weaponAttribute(ItemStack stack, LocalPlayer p, Holder<Attribute> attribute) {
        AttributeInstance current = p.getAttribute(attribute);
        if (current == null) return p.getAttributeBaseValue(attribute);
        AttributeInstance prospective = new AttributeInstance(attribute, ignored -> {});
        prospective.replaceFrom(current);
        // Equipment attributes can lag a same-tick hotbar change; remove every possible held-item ID, not potion/armor effects.
        for (int i = 0; i < 36; i++) {
            p.getInventory().getItem(i).forEachModifier(EquipmentSlot.MAINHAND, (type, modifier) -> {
                if (type.equals(attribute)) prospective.removeModifier(modifier);
            });
        }
        stack.forEachModifier(EquipmentSlot.MAINHAND, (type, modifier) -> {
            if (type.equals(attribute)) prospective.addOrUpdateTransientModifier(modifier);
        });
        return prospective.getValue();
    }

    double scoreWeapon(ItemStack stack, Entity target, LocalPlayer p) {
        if (stack.has(DataComponents.PIERCING_WEAPON)) return Double.NEGATIVE_INFINITY;
        float damage = (float) weaponAttribute(stack, p, Attributes.ATTACK_DAMAGE);
        DamageSource source = stack.getItem().getItemDamageSource(p);
        if (source == null) source = p.damageSources().playerAttack(p);
        damage = enchantmentValue(stack, EnchantmentEffectComponents.DAMAGE, target, damage);
        damage += stack.getItem().getAttackDamageBonus(target, damage, source);
        if (stack.getItem() instanceof MaceItem && MaceItem.canSmashAttack(p)) {
            damage += enchantmentValue(stack, EnchantmentEffectComponents.SMASH_DAMAGE_PER_FALLEN_BLOCK, target, 0.0f)
                * (float) p.fallDistance;
        }
        if (target instanceof LivingEntity living && damage > 0.0f) {
            float armored = CombatRules.getDamageAfterAbsorb(living, damage, source,
                (float) living.getAttributeValue(Attributes.ARMOR), (float) living.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
            float reduction = 1.0f - armored / damage;
            float effectiveness = enchantmentValue(stack, EnchantmentEffectComponents.ARMOR_EFFECTIVENESS, target, reduction);
            damage = (float) Decisions.remainingDamage(damage, effectiveness);
        }
        return Float.isFinite(damage) ? damage : -1.0;
    }

    private static float enchantmentValue(ItemStack stack,
            DataComponentType<List<ConditionalEffect<EnchantmentValueEffect>>> component, Entity target, float value) {
        RandomSource random = RandomSource.create(0L);
        for (var entry : stack.getEnchantments().entrySet()) {
            if (entry.getIntValue() <= 0) continue;
            var enchantment = entry.getKey();
            boolean tagMatches = component == EnchantmentEffectComponents.DAMAGE && (
                enchantment.is(Enchantments.SMITE) && target.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_SMITE)
                || enchantment.is(Enchantments.BANE_OF_ARTHROPODS) && target.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_BANE_OF_ARTHROPODS)
                || enchantment.is(Enchantments.IMPALING) && target.getType().builtInRegistryHolder().is(EntityTypeTags.SENSITIVE_TO_IMPALING));
            for (ConditionalEffect<EnchantmentValueEffect> effect : enchantment.value().getEffects(component)) {
                // Client worlds cannot evaluate arbitrary server loot contexts; known vanilla tag predicates are safe here.
                if (effect.requirements().isEmpty() || tagMatches) {
                    value = effect.effect().process(entry.getIntValue(), random, value);
                }
            }
        }
        return value;
    }

    public void tick(Minecraft mc) {
        if (pendingTarget == null) {
            if (prevSlot >= 0) finish(mc); else HeldSlotManager.clear(this);
            return;
        }
        if (attackSent || !needsReplay && prevSlot >= 0 || !pendingValid(mc)) { finish(mc); return; }
        if (!prepare(mc)) return;
        if (!needsReplay) { finish(mc); return; }
        Entity target = pendingTarget;
        try {
            mc.gameMode.attack(mc.player, target);
            if (attackSent) mc.player.swing(net.minecraft.world.InteractionHand.MAIN_HAND);
        } finally {
            finish(mc);
        }
    }

    private void finish(Minecraft mc) {
        LocalPlayer p = mc == null ? null : mc.player;
        try {
            if (p == null || !Decisions.restore(bool("switchBack"), p == swappedPlayer, mc.level == swappedLevel,
                    HeldSlotManager.holds(this), p.getInventory().getSelectedSlot(), selectedSlot, prevSlot)) return;
            if (inventorySlot >= 0) {
                if (mc.gameMode != null && playerInventoryOpen(p)
                        && ItemStack.isSameItemSameComponents(p.getInventory().getItem(inventorySlot), originalStack)
                        && (p.getMainHandItem().isEmpty() || p.getMainHandItem().getItem() == borrowedStack.getItem())) {
                    InventoryUtil.swapHandlerSlots(mc, inventorySlot, selectedSlot + 36);
                }
            } else {
                InventoryUtil.selectHotbarSlot(mc, prevSlot);
            }
        } finally {
            discard();
        }
    }

    private void discard() {
        prevSlot = -1;
        selectedSlot = -1;
        inventorySlot = -1;
        swappedPlayer = null;
        swappedLevel = null;
        originalStack = ItemStack.EMPTY;
        borrowedStack = ItemStack.EMPTY;
        preparedTarget = null;
        pendingTarget = null;
        requestedAt = settledAt = 0L;
        pendingRaw = needsReplay = attackSent = requestAimed = false;
        HeldSlotManager.clear(this);
    }

    private static boolean playerInventoryOpen(LocalPlayer p) {
        return p.containerMenu == p.inventoryMenu && p.containerMenu.getCarried().isEmpty();
    }

    static final class Decisions {
        private Decisions() {}

        static boolean pendingAge(long ageNanos) {
            return ageNanos >= 0L && ageNanos <= MAX_PENDING_NANOS;
        }

        static boolean attributeMatches(double actual, double expected) {
            return Double.isFinite(actual) && Double.isFinite(expected) && Math.abs(actual - expected) <= 1.0e-5;
        }

        static long settleNanos(double delayTicks, long nativeTickNanos, int latencyMillis, int safetyMillis) {
            if (!Double.isFinite(delayTicks) || delayTicks <= 0.0 || nativeTickNanos <= 0L || safetyMillis < 0) return Long.MAX_VALUE;
            double ticks = Math.ceil(delayTicks) + 1.0;
            double nanos = ticks * Math.max(50_000_000L, nativeTickNanos)
                + (Math.clamp(latencyMillis, 0, 2000) + (long) safetyMillis) * 1_000_000.0;
            return Double.isFinite(nanos) && nanos <= MAX_PENDING_NANOS ? (long) Math.ceil(nanos) : Long.MAX_VALUE;
        }

        static boolean charged(double currentDelay, double weaponDelay, DoubleUnaryOperator scale) {
            if (!Double.isFinite(currentDelay) || !Double.isFinite(weaponDelay) || currentDelay <= 0.0 || weaponDelay <= 0.0) return false;
            // (ticker + currentDelay - weaponDelay) / currentDelay >= 1 iff ticker >= weaponDelay, even after saturation.
            return scale.applyAsDouble(currentDelay - weaponDelay) >= 1.0;
        }

        static int bestSlot(double[] damage, boolean[] disablesShield, int selected) {
            int best = selected;
            for (int i = 0; i < damage.length; i++) {
                if (!Double.isFinite(damage[i])) continue;
                if (disablesShield[i] && !disablesShield[best]
                        || disablesShield[i] == disablesShield[best] && damage[i] > damage[best]) best = i;
            }
            return best;
        }

        static double remainingDamage(double damage, double armorEffectiveness) {
            return damage * (1.0 - Math.clamp(armorEffectiveness, 0.0, 1.0));
        }

        static boolean restore(boolean switchBack, boolean samePlayer, boolean sameWorld, boolean ownsSlot,
                               int selected, int borrowed, int previous) {
            return switchBack && samePlayer && sameWorld && ownsSlot && previous >= 0 && previous < 9
                && borrowed >= 0 && borrowed < 9 && selected == borrowed;
        }
    }
}
