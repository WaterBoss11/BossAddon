/*
 * Adapted from LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package com.boss.pvp.module.movement;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.CombatManager;
import com.boss.pvp.util.InventoryUtil;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.RotationManager;
import com.boss.pvp.util.pvp.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public final class ElytraPursuitModule extends Module {
    private static final Set<String> PLAYER_IDS = Set.of("minecraft:player");
    private static final int ROTATION_PRIORITY = RotationManager.PRIORITY_LOW;
    private static final int SLOT_PRIORITY = 25;
    private LocalPlayer contextPlayer;
    private Level contextLevel;
    private boolean controlling;
    private boolean hadAim;
    private float lastYaw;
    private float lastPitch;
    private int manualPause;
    private int rocketsUsed;
    private long nextRocketTick;
    private long controlTick = Long.MIN_VALUE;

    public ElytraPursuitModule() {
        super(BossPvpAddon.ID + ":elytrapursuit", "ElytraPursuit",
            "Hold forward to steer an existing glide toward a player. No takeoff, attacks, or automatic host toggles.");
        add(new BoolSetting("combatTargetOnly", "Observed combat target only", true));
        add(new BoolSetting("glidingTargetsOnly", "Gliding targets only", true));
        add(new DoubleSetting("range", "Target range", 64, 8, 128, 4));
        add(new DoubleSetting("separation", "Stand-off distance", 6, 3, 16, 0.5));
        add(new DoubleSetting("leadTicks", "Bounded target lead (ticks)", 2, 0, 4, 0.5));
        add(new DoubleSetting("yawTurn", "Maximum yaw turn per tick", 12, 2, 30, 1));
        add(new DoubleSetting("pitchTurn", "Maximum pitch turn per tick", 8, 1, 15, 1));
        add(new BoolSetting("rockets", "Use bounded native rockets", false)
            .description("Optional normal item use, not a Flight mode. No explosive rockets; server acceptance is not assumed."));
        add(new IntSetting("rocketInterval", "Minimum rocket interval (ticks)", 40, 20, 100, 5));
        add(new IntSetting("rocketBudget", "Rocket attempts per glide", 2, 1, 5, 1)
            .description("GUI, mouse overrides and target changes do not refill the budget."));
        add(new BoolSetting("experimentalBrake", "Experimental client-motion brake", false)
            .description("Optional separation/obstacle braking. Servers may reject or correct it; not guaranteed collision avoidance."));
    }

    public void tick(Minecraft mc) {
        controlling = false;
        if (mc == null || mc.player == null || mc.level == null || !isEnabled()) { reset(); return; }
        LocalPlayer player = mc.player;
        if (contextPlayer != player || contextLevel != mc.level) {
            reset();
            contextPlayer = player;
            contextLevel = mc.level;
            nextRocketTick = mc.level.getGameTime() + 20;
            return;
        }
        if (!player.isFallFlying()) {
            stopSteering();
            rocketsUsed = 0;
            nextRocketTick = mc.level.getGameTime() + 20;
            return;
        }
        if (manualPause > 0) manualPause--;
        if (!CoreMovementSupport.ready(mc) || !activeInput(mc) || manualPause > 0
            || player.getAbilities().flying || PvpUtil.hostEnabled("flight") || player.isInLiquid() || player.isInPowderSnow
            || player.isUsingItem() || CombatManager.isCombatPaused()) { stopSteering(); return; }
        Player target = target(mc, player);
        if (target == null) { stopSteering(); return; }
        RotationManager.Request committed = RotationManager.committed();
        if (committed != null && (committed.priority() > ROTATION_PRIORITY
            || committed.priority() == ROTATION_PRIORITY && (!hadAim || Math.abs(committed.pitch() - lastPitch) > 1
                || Math.abs(CoreMovementSupport.turn(lastYaw, committed.yaw(), 180) - lastYaw) > 1))) {
            stopSteering();
            return;
        }
        Vec3 velocity = player.getDeltaMovement();
        if (!Double.isFinite(velocity.lengthSqr())) { stopSteering(); return; }
        Vec3 toTarget = target.getBoundingBox().getCenter().subtract(player.getEyePosition());
        double distance = toTarget.length();
        if (distance <= CoreMovementSupport.EPSILON) { stopSteering(); return; }
        Vec3 direction = toTarget.scale(1 / distance);
        double closing = velocity.subtract(target.getDeltaMovement()).dot(direction);
        boolean separating = CoreMovementSupport.needsSeparation(distance, decimal("separation"), closing, 6);
        Vec3 lead = target.getDeltaMovement();
        if (lead.lengthSqr() > 9) lead = lead.normalize().scale(3);
        Vec3 aimPoint = separating
            ? player.getEyePosition().subtract(direction.scale(8)).add(0, 3, 0)
            : target.getBoundingBox().getCenter().add(lead.scale(decimal("leadTicks")))
                .subtract(direction.scale(decimal("separation")));
        RotationUtil.Rotation current = RotationUtil.playerRotation(player);
        RotationUtil.Rotation desired = RotationUtil.lookingAt(aimPoint, player.getEyePosition());
        float pitch = (float) Math.clamp(desired.pitch(), -30, 30);
        RotationUtil.Rotation look = RotationUtil.normalizeToSensitivity(new RotationUtil.Rotation(
            CoreMovementSupport.turn(current.yaw(), desired.yaw(), (float) decimal("yawTurn")),
            CoreMovementSupport.turn(current.pitch(), pitch, (float) decimal("pitchTurn"))), current);
        Vec3 proposedPath = Vec3.directionFromRotation(look.pitch(), look.yaw()).scale(Math.max(0.1, velocity.length()) * 3);
        AABB turnCorridor = player.getBoundingBox().expandTowards(velocity.scale(3))
            .minmax(player.getBoundingBox().expandTowards(proposedPath));
        boolean clear = CoreMovementSupport.clearPath(mc, player, turnCorridor, Vec3.ZERO);
        if (!clear) {
            stopSteering();
            if (bool("experimentalBrake")) brake(mc, player, velocity);
            return;
        }
        RotationManager.submit(look.yaw(), look.pitch(), ROTATION_PRIORITY, false);
        RotationUtil.apply(player, look, false);
        lastYaw = look.yaw();
        lastPitch = look.pitch();
        hadAim = true;
        controlling = true;
        controlTick = mc.level.getGameTime();
        if (separating && bool("experimentalBrake")) brake(mc, player, velocity);
        boolean aligned = Math.abs(CoreMovementSupport.turn(look.yaw(), desired.yaw(), 180) - look.yaw()) <= 12;
        if (!bool("rockets") || separating || !aligned || player.getFallFlyingTicks() < 20
            || rocketsUsed >= integer("rocketBudget") || mc.level.getGameTime() < nextRocketTick
            || distance <= decimal("separation") + Math.max(10, velocity.length() * 12)
            || !CoreMovementSupport.clearPath(mc, player,
                player.getBoundingBox().expandTowards(velocity.scale(6))
                    .minmax(player.getBoundingBox().expandTowards(proposedPath.scale(2))), Vec3.ZERO)) {
            HeldSlotManager.clear(this);
            return;
        }
        long attack = CombatManager.lastAttackMs();
        if (mc.options.keyAttack.isDown() || attack > 0 && System.currentTimeMillis() - attack < 350) {
            HeldSlotManager.clear(this);
            return;
        }
        int slot = rocketSlot(player);
        if (slot < 0) { HeldSlotManager.clear(this); return; }
        ItemStack stack = slot == 9 ? player.getOffhandItem() : player.getInventory().getItem(slot);
        if (player.getCooldowns().isOnCooldown(stack)) { HeldSlotManager.clear(this); return; }
        if (!HeldSlotManager.tryAcquire(this, SLOT_PRIORITY)) return;
        nextRocketTick = mc.level.getGameTime() + integer("rocketInterval");
        rocketsUsed++;
        useRocket(mc, player, slot);
    }

    private void brake(Minecraft mc, LocalPlayer player, Vec3 velocity) {
        player.setDeltaMovement(velocity.scale(0.65));
        controlling = true;
        controlTick = mc.level.getGameTime();
    }

    private boolean activeInput(Minecraft mc) {
        return mc.options.keyUp.isDown() && !mc.options.keyDown.isDown() && !mc.options.keyLeft.isDown()
            && !mc.options.keyRight.isDown() && !mc.options.keyShift.isDown() && !mc.options.keyJump.isDown()
            && !mc.options.keyUse.isDown();
    }

    private Player target(Minecraft mc, LocalPlayer player) {
        LivingEntity current = CombatManager.currentTarget(mc);
        if (current instanceof Player other && validTarget(mc, player, other)) return other;
        if (bool("combatTargetOnly")) return null;
        LivingEntity nearest = PvpUtil.findTarget(mc, player, decimal("range"), 180, PLAYER_IDS, true, "Distance");
        return nearest instanceof Player other && validTarget(mc, player, other) ? other : null;
    }

    private boolean validTarget(Minecraft mc, LocalPlayer player, Player other) {
        return other != player && other.isAlive() && !other.isRemoved() && !other.isSpectator()
            && other.level() == mc.level && !PvpUtil.isProtectedPlayer(mc, other) && player.hasLineOfSight(other)
            && (!bool("glidingTargetsOnly") || other.isFallFlying())
            && other.distanceToSqr(player) <= decimal("range") * decimal("range");
    }

    private boolean safeRocket(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.FIREWORK_ROCKET)) return false;
        Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
        return fireworks == null || fireworks.explosions().isEmpty()
            && fireworks.flightDuration() >= 0 && fireworks.flightDuration() <= 3;
    }

    private int rocketSlot(LocalPlayer player) {
        if (safeRocket(player.getOffhandItem())) return 9;
        if (safeRocket(player.getMainHandItem())) return player.getInventory().getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) if (safeRocket(player.getInventory().getItem(slot))) return slot;
        return -1;
    }

    private void useRocket(Minecraft mc, LocalPlayer player, int slot) {
        int previous = player.getInventory().getSelectedSlot();
        Level world = mc.level;
        try {
            if (!HeldSlotManager.holds(this) || !player.isFallFlying()) return;
            if (slot < 9) InventoryUtil.selectHotbarSlot(mc, slot);
            InteractionHand hand = slot == 9 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            ItemStack stack = player.getItemInHand(hand);
            if (slot < 9 && player.getInventory().getSelectedSlot() != slot) return;
            if (!safeRocket(stack) || player.getCooldowns().isOnCooldown(stack)) return;
            mc.gameMode.useItem(player, hand);
        } finally {
            try {
                if (slot < 9 && previous != slot && mc.player == player && mc.level == world
                    && HeldSlotManager.holds(this) && player.getInventory().getSelectedSlot() == slot) {
                    InventoryUtil.selectHotbarSlot(mc, previous);
                }
            } finally {
                HeldSlotManager.clear(this);
            }
        }
    }

    @Override
    public void onMouseRotation(double dx, double dy) {
        if (Math.abs(dx) + Math.abs(dy) <= 0.01) return;
        manualPause = 10;
        stopSteering();
    }

    public boolean controlsMovement() {
        Minecraft mc = Minecraft.getInstance();
        return isEnabled() && controlling && CoreMovementSupport.ready(mc) && mc.player == contextPlayer
            && mc.level == contextLevel && mc.player.isFallFlying() && activeInput(mc)
            && manualPause == 0 && mc.level.getGameTime() - controlTick <= 1;
    }

    private void stopSteering() {
        controlling = false;
        hadAim = false;
        HeldSlotManager.clear(this);
    }

    private void reset() {
        stopSteering();
        contextPlayer = null;
        contextLevel = null;
        manualPause = 0;
        rocketsUsed = 0;
        nextRocketTick = 0;
        controlTick = Long.MIN_VALUE;
    }

    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    @Override public void onGameJoin() { reset(); }
    @Override public void onGameLeft() { reset(); }
}
