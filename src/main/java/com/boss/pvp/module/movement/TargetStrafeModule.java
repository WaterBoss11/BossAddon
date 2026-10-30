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
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.CombatManager;
import com.boss.pvp.util.pvp.PvpUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.Set;

public final class TargetStrafeModule extends Module {
    private static final Set<String> PLAYER_IDS = Set.of("minecraft:player");
    private LocalPlayer contextPlayer;
    private Level contextLevel;
    private boolean controlling;
    private int direction = 1;
    private int reverseCooldown;
    private long controlTick = Long.MIN_VALUE;

    public TargetStrafeModule() {
        super(BossPvpAddon.ID + ":targetstrafe", "TargetStrafe",
            "Hold forward to orbit a player; left/right choose direction, back/jump/sneak cancel. Does not add speed.");
        add(new BoolSetting("combatTargetOnly", "Observed combat target only", true));
        add(new DoubleSetting("range", "Target range", 6, 3, 10, 0.5));
        add(new DoubleSetting("radius", "Orbit radius", 2.8, 1.5, 4, 0.1));
        add(new DoubleSetting("speedCap", "Orbit speed cap", 0.3, 0.1, 0.4, 0.01)
            .description("Caps existing horizontal motion; does not supply a generic Speed/Strafe boost."));
    }

    public void tick(Minecraft mc) {
        controlling = false;
        if (!isEnabled() || !CoreMovementSupport.ready(mc)) { reset(); return; }
        LocalPlayer player = mc.player;
        if (contextPlayer != player || contextLevel != mc.level) {
            reset();
            contextPlayer = player;
            contextLevel = mc.level;
            return;
        }
        if (reverseCooldown > 0) reverseCooldown--;
        if (!activeInput(mc) || !player.onGround() || !CoreMovementSupport.groundState(player)
            || player.isUsingItem() || player.getDeltaMovement().y > 0.0 || CombatManager.isCombatPaused()) return;
        Player target = target(mc, player);
        if (target == null) return;
        double speed = Math.min(decimal("speedCap"), Math.hypot(player.getDeltaMovement().x, player.getDeltaMovement().z));
        if (speed < 0.015) return;
        boolean left = mc.options.keyLeft.isDown(), right = mc.options.keyRight.isDown();
        boolean manualDirection = left != right;
        if (manualDirection) direction = left ? -1 : 1;
        double x = target.getX() - player.getX(), z = target.getZ() - player.getZ();
        CoreMovementSupport.Horizontal step = CoreMovementSupport.orbit(x, z, decimal("radius"), speed, direction);
        if (!safeStep(mc, player, step) || player.horizontalCollision) {
            if (manualDirection || reverseCooldown > 0) return;
            CoreMovementSupport.Horizontal reversed = CoreMovementSupport.orbit(x, z, decimal("radius"), speed, -direction);
            if (!safeStep(mc, player, reversed)) return;
            direction = -direction;
            reverseCooldown = 8;
            step = reversed;
        }
        if (Math.hypot(step.x(), step.z()) < CoreMovementSupport.EPSILON) return;
        player.setDeltaMovement(step.x(), player.getDeltaMovement().y, step.z());
        controlling = true;
        controlTick = mc.level.getGameTime();
    }

    private boolean activeInput(Minecraft mc) {
        return mc.options.keyUp.isDown() && !mc.options.keyDown.isDown() && !mc.options.keyShift.isDown()
            && !mc.options.keyJump.isDown() && !mc.options.keyUse.isDown();
    }

    private boolean safeStep(Minecraft mc, LocalPlayer player, CoreMovementSupport.Horizontal step) {
        Vec3 movement = new Vec3(step.x(), 0, step.z());
        return CoreMovementSupport.clearPath(mc, player, player.getBoundingBox(), movement)
            && Double.isFinite(CoreMovementSupport.groundDrop(mc, player, player.getBoundingBox().move(movement), 0.55));
    }

    private Player target(Minecraft mc, LocalPlayer player) {
        LivingEntity current = CombatManager.currentTarget(mc);
        if (current instanceof Player other && validTarget(player, mc, other)) return other;
        if (bool("combatTargetOnly")) return null;
        LivingEntity nearest = PvpUtil.findTarget(mc, player, decimal("range"), 180, PLAYER_IDS, true, "Distance");
        return nearest instanceof Player other && validTarget(player, mc, other) ? other : null;
    }

    private boolean validTarget(LocalPlayer player, Minecraft mc, Player other) {
        return other != player && other.isAlive() && !other.isRemoved() && !other.isSpectator()
            && other.level() == mc.level && !PvpUtil.isProtectedPlayer(mc, other)
            && other.distanceToSqr(player) <= decimal("range") * decimal("range")
            && Math.abs(other.getY() - player.getY()) <= 2 && player.hasLineOfSight(other);
    }

    public boolean controlsMovement() {
        Minecraft mc = Minecraft.getInstance();
        return isEnabled() && controlling && CoreMovementSupport.ready(mc) && mc.player == contextPlayer
            && mc.level == contextLevel && activeInput(mc) && mc.player.onGround()
            && CoreMovementSupport.groundState(mc.player) && !mc.player.isUsingItem()
            && mc.level.getGameTime() - controlTick <= 1;
    }

    private void reset() {
        controlling = false;
        contextPlayer = null;
        contextLevel = null;
        direction = 1;
        reverseCooldown = 0;
        controlTick = Long.MIN_VALUE;
    }

    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    @Override public void onGameJoin() { reset(); }
    @Override public void onGameLeft() { reset(); }
}
