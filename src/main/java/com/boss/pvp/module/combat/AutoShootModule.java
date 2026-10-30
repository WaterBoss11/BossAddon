package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.CombatManager;
import com.boss.pvp.util.InventoryUtil;
import com.boss.pvp.util.MenuMode;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.ProjectileMath;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.RotationUtil;

import autismclient.modules.Module;
import autismclient.api.module.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class AutoShootModule extends Module {

    private long nextThrowMs;
    private LocalPlayer cooldownPlayer;
    private Level cooldownLevel;

    public AutoShootModule() {
        super(BossPvpAddon.ID + ":autoshoot", "AutoShoot", "Throws snowballs, eggs, or pearls at targets.");
        add(new BoolSetting("snowball", "Snowball", true).group("Projectiles"));
        add(new BoolSetting("egg", "Egg", false).group("Projectiles"));
        add(new BoolSetting("pearl", "Ender pearl", false).group("Projectiles"));
        add(new IntSetting("delay", "Delay (ms)", 500, 0, 3000, 10).visibleWhen(MenuMode::advanced).group("General"));
        add(new BoolSetting("onlyVisible", "Only visible targets", true).visibleWhen(MenuMode::advanced).group("General"));
        add(new BoolSetting("switchBack", "Switch back after", true).visibleWhen(MenuMode::advanced).group("General"));
    }

    @Override
    public void onDisable() {
        clear();
    }

    @Override
    public void onGameLeft() {
        clear();
    }

    private void clear() {
        HeldSlotManager.clear(this);
        nextThrowMs = 0L;
        cooldownPlayer = null;
        cooldownLevel = null;
    }

    public void tick(Minecraft mc) {
        HeldSlotManager.clear(this);
        LocalPlayer p = mc == null ? null : mc.player;
        if (p == null || mc.level == null) {
            clear();
            return;
        }
        if (p != cooldownPlayer || mc.level != cooldownLevel) {
            clear();
            cooldownPlayer = p;
            cooldownLevel = mc.level;
        }
        if (mc.gameMode == null || mc.getConnection() == null || mc.gui.screen() != null || p.isSpectator()
                || p.isUsingItem() || CombatManager.isCombatPaused() || PvpUtil.hostEnabled("hide")) return;
        if (!(mc.crosshairPickEntity instanceof Player target) || target == p || !target.isAlive()
                || target.isRemoved() || target.isSpectator() || target.level() != mc.level
                || target.distanceToSqr(p) > 16.0 * 16.0 || PvpUtil.isProtectedPlayer(mc, target)) return;
        if (bool("onlyVisible") && !PvpUtil.canSeeEntity(mc, p, target)) return;
        long now = System.currentTimeMillis();
        if (now < nextThrowMs) return;
        int slot = throwableSlot(p);
        if (slot < 0) return;

        Vec3 movement = p.getKnownMovement();
        Vec3 inherited = new Vec3(movement.x, p.onGround() ? 0.0 : movement.y, movement.z);
        Vec3 origin = ProjectileMath.origin(ProjectileMath.Kind.THROWABLE, p.getEyePosition(), p.getYRot());
        ProjectileMath.Shot shot = ProjectileMath.solve(ProjectileMath.Kind.THROWABLE, origin, inherited,
            target.getBoundingBox().getCenter(), target.getKnownMovement(), 40);
        if (shot == null) return;
        RotationUtil.Rotation previous = RotationUtil.playerRotation(p);
        RotationUtil.Rotation rotation = RotationUtil.normalizeToSensitivity(
            new RotationUtil.Rotation(shot.yaw(), shot.pitch()), previous);
        Vec3 velocity = ProjectileMath.launchVelocity(ProjectileMath.Kind.THROWABLE, rotation.yaw(), rotation.pitch(), inherited);
        if (!ProjectileMath.clearShot(mc.level, p, target, ProjectileMath.Kind.THROWABLE,
                origin, velocity, Math.min(40, (int) Math.ceil(shot.ticks()) + 2))) return;
        if (!HeldSlotManager.tryAcquire(this, HeldSlotManager.PRIORITY_SHIELDBREAKER)) {
            HeldSlotManager.clear(this);
            return;
        }

        int previousSlot = p.getInventory().getSelectedSlot();
        Level level = mc.level;
        float previousYaw = p.yRotO;
        float previousPitch = p.xRotO;
        nextThrowMs = now + 100L;
        try {
            InventoryUtil.selectHotbarSlot(mc, slot);
            if (p.getInventory().getSelectedSlot() != slot || !throwable(p, p.getMainHandItem())
                    || PvpUtil.isProtectedPlayer(mc, target)) return;
            // The native use packet includes these angles; restore locally before a frame can render them.
            RotationUtil.apply(p, rotation, false);
            if (mc.gameMode.useItem(p, InteractionHand.MAIN_HAND).consumesAction()) {
                p.swing(InteractionHand.MAIN_HAND);
                nextThrowMs = now + Math.max(50L, integer("delay"));
                CombatManager.pauseCombat(1);
            }
        } finally {
            try {
                RotationUtil.apply(p, previous, false);
                p.yRotO = previousYaw;
                p.xRotO = previousPitch;
                if (bool("switchBack") && mc.player == p && mc.level == level && HeldSlotManager.holds(this)
                        && p.getInventory().getSelectedSlot() == slot && previousSlot >= 0 && previousSlot < 9) {
                    InventoryUtil.selectHotbarSlot(mc, previousSlot);
                }
            } finally {
                HeldSlotManager.clear(this);
            }
        }
    }

    private int throwableSlot(LocalPlayer p) {
        int selected = p.getInventory().getSelectedSlot();
        if (throwable(p, p.getMainHandItem())) return selected;
        for (int i = 0; i < 9; i++) {
            if (throwable(p, p.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private boolean throwable(LocalPlayer p, ItemStack stack) {
        return !stack.isEmpty() && !p.getCooldowns().isOnCooldown(stack)
            && (bool("snowball") && stack.is(Items.SNOWBALL) || bool("egg") && stack.is(Items.EGG)
                || bool("pearl") && stack.is(Items.ENDER_PEARL));
    }
}
