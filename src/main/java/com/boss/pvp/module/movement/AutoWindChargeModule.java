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

import autismclient.api.module.ChoiceSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.KeybindSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.InputUtil;
import com.boss.pvp.util.InventoryUtil;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.RotationManager;
import com.boss.pvp.util.pvp.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class AutoWindChargeModule extends Module {
    private static final int SLOT_PRIORITY = 82;
    private static final int ROTATION_PRIORITY = 75;
    private LocalPlayer contextPlayer;
    private Level contextLevel;
    private boolean triggerWasDown = true;
    private boolean aimSubmitted;
    private int armedTicks;
    private long nextUseTick;

    public AutoWindChargeModule() {
        super(BossPvpAddon.ID + ":autowindcharge", "AutoWindCharge",
            "Hold jump/key for one wind charge per press, with real aim and a bounded landing estimate. No automatic jumping.");
        add(new ChoiceSetting("trigger", "Trigger", "Jump press", "Jump press", "Bound key"));
        add(new KeybindSetting("triggerKey", "Wind-charge key", -1)
            .description("Only used with Bound key. Hold to arm; release and press again for another charge. Attack/use override it."));
        add(new IntSetting("delayTicks", "Minimum use interval (ticks)", 20, 10, 80, 1));
    }

    public void tick(Minecraft mc) {
        if (!isEnabled() || !CoreMovementSupport.ready(mc) || !InputUtil.canRunKeybinds(mc)) { reset(); return; }
        LocalPlayer player = mc.player;
        if (contextPlayer != player || contextLevel != mc.level) {
            reset();
            contextPlayer = player;
            contextLevel = mc.level;
            triggerWasDown = triggerDown(mc);
            return;
        }
        boolean down = triggerDown(mc);
        boolean pressed = down && !triggerWasDown;
        triggerWasDown = down;
        if (!CoreMovementSupport.groundState(player) || player.isUsingItem() || PvpUtil.hostEnabled("flight")
            || player.hasEffect(MobEffects.SLOW_FALLING) || player.hasEffect(MobEffects.LEVITATION)
            || mc.options.keyShift.isDown() || mc.options.keyUse.isDown() || mc.options.keyAttack.isDown()) {
            cancel();
            return;
        }
        if (pressed && mc.level.getGameTime() >= nextUseTick) armedTicks = 24;
        if (!down || armedTicks <= 0) { cancel(); return; }
        armedTicks--;
        if (player.onGround() && "Jump press".equals(choice("trigger")) || player.getDeltaMovement().y > 0.1) {
            releaseAim();
            return;
        }
        CoreMovementSupport.Landing landing = CoreMovementSupport.landing(mc, player, 7);
        int slot = chargeSlot(player);
        if (landing == null || slot < 0) { releaseAim(); return; }
        ItemStack stack = slot == 9 ? player.getOffhandItem() : player.getInventory().getItem(slot);
        if (player.getCooldowns().isOnCooldown(stack)) { releaseAim(); return; }
        RotationUtil.Rotation look = RotationUtil.normalizeToSensitivity(
            RotationUtil.lookingAt(landing.feet(), player.getEyePosition()), RotationUtil.playerRotation(player));
        if (look.pitch() < 60 || !validAim(mc, player, look, landing.feet())) { releaseAim(); return; }
        RotationManager.Request committed = RotationManager.committed();
        if (committed != null && committed.priority() > ROTATION_PRIORITY) { releaseAim(); return; }
        if (!HeldSlotManager.tryAcquire(this, SLOT_PRIORITY)) { aimSubmitted = false; return; }
        RotationManager.submit(look.yaw(), look.pitch(), ROTATION_PRIORITY, false);
        boolean aligned = aimSubmitted && committed != null && committed.priority() == ROTATION_PRIORITY
            && Math.abs(CoreMovementSupport.turn(committed.yaw(), look.yaw(), 180) - committed.yaw()) <= 2
            && Math.abs(committed.pitch() - look.pitch()) <= 2;
        aimSubmitted = true;
        if (!aligned) return;
        RotationUtil.apply(player, look, false);
        nextUseTick = mc.level.getGameTime() + integer("delayTicks");
        try {
            useCharge(mc, player, slot);
        } finally {
            cancel();
        }
    }

    private boolean validAim(Minecraft mc, LocalPlayer player, RotationUtil.Rotation look, Vec3 landing) {
        Vec3 eyes = player.getEyePosition();
        Vec3 end = eyes.add(Vec3.directionFromRotation(look.pitch(), look.yaw()).scale(6));
        if (!CoreMovementSupport.loaded(mc.level, new AABB(eyes, end))) return false;
        BlockHitResult hit = mc.level.clip(new ClipContext(eyes, end,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, player));
        if (hit.getType() != HitResult.Type.BLOCK || hit.getDirection() != Direction.UP
            || hit.getLocation().distanceToSqr(landing) > 0.75 * 0.75
            || CoreMovementSupport.hazardous(mc.level.getBlockState(hit.getBlockPos()))) return false;
        for (Entity entity : mc.level.getEntities(player, new AABB(eyes, hit.getLocation()).inflate(0.25),
                entity -> entity.isAlive() && entity.isPickable() && !entity.isSpectator())) {
            if (entity.getBoundingBox().inflate(0.2).clip(eyes, hit.getLocation()).isPresent()) return false;
        }
        return true;
    }

    private int chargeSlot(LocalPlayer player) {
        if (player.getOffhandItem().is(Items.WIND_CHARGE)) return 9;
        if (player.getMainHandItem().is(Items.WIND_CHARGE)) return player.getInventory().getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) if (player.getInventory().getItem(slot).is(Items.WIND_CHARGE)) return slot;
        return -1;
    }

    private void useCharge(Minecraft mc, LocalPlayer player, int slot) {
        int previous = player.getInventory().getSelectedSlot();
        Level world = mc.level;
        try {
            if (!HeldSlotManager.holds(this)) return;
            if (slot < 9) InventoryUtil.selectHotbarSlot(mc, slot);
            InteractionHand hand = slot == 9 ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
            ItemStack stack = player.getItemInHand(hand);
            if (slot < 9 && player.getInventory().getSelectedSlot() != slot) return;
            if (!stack.is(Items.WIND_CHARGE) || player.getCooldowns().isOnCooldown(stack)) return;
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

    private boolean triggerDown(Minecraft mc) {
        if ("Jump press".equals(choice("trigger"))) return mc.options.keyJump.isDown();
        try {
            return InputUtil.isBindPressed(mc, Integer.parseInt(value("triggerKey")));
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private void releaseAim() {
        aimSubmitted = false;
        HeldSlotManager.clear(this);
    }

    private void cancel() {
        armedTicks = 0;
        releaseAim();
    }

    private void reset() {
        cancel();
        contextPlayer = null;
        contextLevel = null;
        triggerWasDown = true;
        nextUseTick = 0;
    }

    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    @Override public void onGameJoin() { reset(); }
    @Override public void onGameLeft() { reset(); }
}
