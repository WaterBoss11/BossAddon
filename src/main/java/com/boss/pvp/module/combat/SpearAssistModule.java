/*
 * Charge-window and lunge-target planning adapted from LiquidBounce's ModuleSpearKill.kt
 * and SpearKillTargetFinder.kt (https://github.com/CCBlueX/LiquidBounce).
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
package com.boss.pvp.module.combat;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ChoiceSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.CombatManager;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.RotationManager;
import com.boss.pvp.util.pvp.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static com.boss.pvp.module.combat.SpecializedCombatDecisions.ChargeWindow;

public final class SpearAssistModule extends Module implements HeldSlotManager.Revocable {
    private static final int SLOT_PRIORITY = SpecializedCombatDecisions.PREEMPTIBLE_SLOT_PRIORITY;
    private static final int ROTATION_PRIORITY = 55;
    private static final int SERVER_WAIT_TICKS = 8;
    private LocalPlayer contextPlayer;
    private ClientLevel contextLevel;
    private Player target;
    private ItemStack ownedUse = ItemStack.EMPTY;
    private InteractionHand ownedHand;
    private InteractionHand restartHand;
    private ItemStack restartStack = ItemStack.EMPTY;
    private int restartTick;
    private int lastUseTicks = -1;
    private boolean aimSubmitted;
    private RotationUtil.Rotation beforeAim;
    private RotationUtil.Rotation lastAim;
    private String status = "Idle";

    private Vec3 lungeStep = Vec3.ZERO;
    private Vec3 queuedMove = Vec3.ZERO;
    private Vec3 expectedServerPosition;
    private int outwardSteps;
    private int returnSteps;
    private int lungeAge;
    private int serverWait;
    private int queuedTick;
    private int lastMoveTick = Integer.MIN_VALUE;
    private boolean lungeUsed;
    private CompletableFuture<ServerSample> serverSample;

    private record ServerSample(Vec3 position, boolean allowed) {}

    public SpearAssistModule() {
        super(BossPvpAddon.ID + ":spearassist", "SpearAssist",
            "Assists a manually held kinetic spear. Damage remains server-controlled; no jab/attack packets.");
        add(new DoubleSetting("range", "Target range", 6.0, 1.0, 8.0, 0.1));
        add(new DoubleSetting("fov", "Aim cone (degrees)", 35.0, 1.0, 90.0, 1.0));
        add(new BoolSetting("aim", "Assist aim while using", true));
        add(new BoolSetting("recharge", "Restart spent charge while use is held", true)
            .description("Uses the item's native windup and inclusive damage-window end, not an attack-speed timer."));
        add(new ChoiceSetting("lunge", "Experimental lunge / return", "Off", "Off", "Integrated server host")
            .description("Off by default. Integrated-server host only, not remote/LAN clients. Each step needs server-thread validation and position acknowledgement."));
        add(new DoubleSetting("lungeDistance", "Maximum lunge distance", 0.8, 0.1, 1.5, 0.1)
            .visibleWhen(() -> !"Off".equals(choice("lunge"))));
        add(new DoubleSetting("lungeStep", "Maximum extra movement per tick", 0.25, 0.1, 0.35, 0.05)
            .visibleWhen(() -> !"Off".equals(choice("lunge"))));
    }

    @Override public void onLeaseRevoked() { reset(Minecraft.getInstance()); }

    @Override public String info() { return status; }
    @Override public void onDisable() { reset(Minecraft.getInstance()); }
    @Override public void onGameLeft() { reset(Minecraft.getInstance()); }
    @Override public void onGameJoin() { reset(Minecraft.getInstance()); }

    public void tick(Minecraft mc) {
        if (!ready(mc)) { reset(mc); return; }
        LocalPlayer player = mc.player;
        if (contextPlayer != player || contextLevel != mc.level) {
            reset(mc);
            contextPlayer = player;
            contextLevel = mc.level;
        }
        if (lungeStep.lengthSqr() == 0 && restartHand == null) target = findTarget(mc);
        if (target == null || !validTarget(mc, target) || !mc.options.keyUse.isDown()) { reset(mc); return; }
        if (restartHand != null) {
            restart(mc);
            return;
        }
        if (!player.isUsingItem()) { reset(mc); return; }
        ItemStack using = player.getUseItem();
        KineticWeapon weapon = using.get(DataComponents.KINETIC_WEAPON);
        if (weapon == null || weapon.damageConditions().isEmpty()) { reset(mc); return; }
        if (!HeldSlotManager.tryAcquire(this, SLOT_PRIORITY)) { reset(mc); return; }
        int used = player.getTicksUsingItem();
        if (used < lastUseTicks) {
            clearLunge();
            lungeUsed = false;
            ownedUse = ItemStack.EMPTY;
        }
        lastUseTicks = used;
        if (ownedUse != using) ownedUse = ItemStack.EMPTY;
        RotationUtil.Rotation look = bool("aim")
            ? RotationUtil.normalizeToSensitivity(RotationUtil.lookingAt(target.getBoundingBox().getCenter(), player.getEyePosition()),
                RotationUtil.playerRotation(player))
            : RotationUtil.playerRotation(player);
        AttackRange range = player.getAttackRangeWith(using);
        double rayLength = Math.max(decimal("range"), range.effectiveMaxRange(player) + player.getDeltaMovement().length());
        if (protectedRay(mc, player.getEyePosition(), Vec3.directionFromRotation(look.pitch(), look.yaw()), rayLength,
                range.hitboxMargin())) {
            reset(mc);
            return;
        }
        if (bool("aim") && !aim(mc, look)) { clearLunge(); return; }
        ChargeWindow phase = SpecializedCombatDecisions.chargeWindow(used, weapon.delayTicks(), weapon.computeDamageUseDuration());
        if (phase == ChargeWindow.WINDUP) {
            status = "Windup " + used + "/" + weapon.delayTicks();
            clearLunge();
            return;
        }
        if (lungeStep.lengthSqr() > 0) {
            advanceLunge(mc, weapon);
            return;
        }
        if (phase == ChargeWindow.SPENT) {
            status = "Spent";
            if (bool("recharge") && !player.getCooldowns().isOnCooldown(using)) {
                restartHand = player.getUsedItemHand();
                restartStack = using;
                restartTick = player.tickCount;
                mc.gameMode.releaseUsingItem(player);
                ownedUse = ItemStack.EMPTY;
            }
            return;
        }
        Vec3 direction = player.getLookAngle();
        double speed = direction.dot(KineticWeapon.getMotion(player));
        double relative = Math.max(0, speed - direction.dot(KineticWeapon.getMotion(target)));
        boolean speedReady = weapon.damageConditions().orElseThrow().test(used - weapon.delayTicks(), speed, relative, 1.0);
        status = speedReady ? "Damage window (speed ready)" : "Damage window (speed too low)";
        if (!lungeUsed && mc.options.keyAttack.isDown() && integratedHostReady(mc)) startLunge(mc, weapon, range);
    }

    private boolean ready(Minecraft mc) {
        return isEnabled() && mc != null && mc.player != null && mc.level != null && mc.gameMode != null
            && mc.getConnection() != null && mc.player.isAlive() && !mc.player.isSpectator() && !mc.isPaused()
            && mc.gui.screen() == null && mc.player.containerMenu == mc.player.inventoryMenu
            && mc.player.inventoryMenu.getCarried().isEmpty() && !CombatManager.isCombatPaused() && !PvpUtil.hostEnabled("hide");
    }

    private Player findTarget(Minecraft mc) {
        var shared = CombatManager.currentTarget(mc);
        if (shared instanceof Player player && validTarget(mc, player)) return player;
        var found = PvpUtil.findTarget(mc, mc.player, decimal("range"), decimal("fov"),
            Set.of("minecraft:player"), true, "Angle");
        return found instanceof Player player && validTarget(mc, player) ? player : null;
    }

    private boolean validTarget(Minecraft mc, Player player) {
        return player != mc.player && player.level() == mc.level && player.isAlive() && !player.isRemoved()
            && !player.isSpectator() && player.isAttackable() && !PvpUtil.isProtectedPlayer(mc, player)
            && player.distanceToSqr(mc.player) <= decimal("range") * decimal("range")
            && PvpUtil.angleTo(mc.player, player) <= decimal("fov") && PvpUtil.canSeeEntity(mc, mc.player, player);
    }

    private boolean aim(Minecraft mc, RotationUtil.Rotation look) {
        RotationManager.Request committed = RotationManager.committed();
        if (committed != null && committed.priority() > ROTATION_PRIORITY) { aimSubmitted = false; return false; }
        RotationManager.submit(look.yaw(), look.pitch(), ROTATION_PRIORITY, false);
        boolean aligned = aimSubmitted && committed != null && committed.priority() == ROTATION_PRIORITY
            && Math.abs(Mth.wrapDegrees(committed.yaw() - look.yaw())) < 3.0f
            && Math.abs(committed.pitch() - look.pitch()) < 3.0f;
        aimSubmitted = true;
        if (!aligned) return false;
        RotationUtil.Rotation current = RotationUtil.playerRotation(mc.player);
        if (lastAim == null || !sameRotation(current, lastAim)) beforeAim = current;
        RotationUtil.apply(mc.player, look, false);
        lastAim = RotationUtil.playerRotation(mc.player);
        return true;
    }

    private void restart(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player.tickCount <= restartTick) return;
        if (player.tickCount - restartTick > 4 || player.getItemInHand(restartHand) != restartStack
            || restartStack.get(DataComponents.KINETIC_WEAPON) == null
            || !HeldSlotManager.tryAcquire(this, SLOT_PRIORITY)) { reset(mc); return; }
        if (player.isUsingItem()) {
            restartHand = null;
            restartStack = ItemStack.EMPTY;
            lastUseTicks = -1;
            lungeUsed = false;
            return;
        }
        AttackRange attackRange = player.getAttackRangeWith(restartStack);
        if (protectedRay(mc, player.getEyePosition(), player.getLookAngle(),
            Math.max(decimal("range"), attackRange.effectiveMaxRange(player) + player.getDeltaMovement().length()),
            attackRange.hitboxMargin())) { reset(mc); return; }
        InteractionHand hand = restartHand;
        if (!player.getCooldowns().isOnCooldown(restartStack)) {
            mc.gameMode.useItem(player, hand);
            if (player.isUsingItem() && player.getUsedItemHand() == hand && player.getUseItem() == restartStack) {
                ownedUse = restartStack;
                ownedHand = hand;
            }
        }
        restartHand = null;
        restartStack = ItemStack.EMPTY;
        lastUseTicks = -1;
        lungeUsed = false;
    }

    private boolean integratedHostReady(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        LocalPlayer player = mc.player;
        return "Integrated server host".equals(choice("lunge")) && mc.isLocalServer() && server != null && server.isRunning()
            && !server.isPaused() && player.onGround() && !player.isPassenger()
            && !player.isFallFlying() && !player.isInWater() && !player.isInLava() && !player.isShiftKeyDown()
            && Math.abs(player.getDeltaMovement().y) < 0.1 && player.hurtTime == 0
            && !mc.options.keyUp.isDown() && !mc.options.keyDown.isDown()
            && !mc.options.keyLeft.isDown() && !mc.options.keyRight.isDown() && !mc.options.keyJump.isDown();
    }

    private void startLunge(Minecraft mc, KineticWeapon weapon, AttackRange range) {
        LocalPlayer player = mc.player;
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(decimal("range")));
        Vec3 hit = target.getBoundingBox().inflate(range.hitboxMargin()).clip(eye, end).orElse(null);
        if (hit == null || Math.abs(target.getY() - player.getY()) > 0.6) return;
        double middle = (range.effectiveMinRange(player) + range.effectiveMaxRange(player)) * 0.5;
        double distance = Math.max(0, eye.distanceTo(hit) - middle);
        if (distance > decimal("lungeDistance")) return;
        int steps = SpecializedCombatDecisions.lungeSteps(distance, decimal("lungeStep"),
            weapon.computeDamageUseDuration() - player.getTicksUsingItem());
        if (steps == 0) return;
        Vec3 horizontal = new Vec3(hit.x - eye.x, 0, hit.z - eye.z).normalize();
        if (horizontal.lengthSqr() < 0.9) return;
        lungeStep = horizontal.scale(distance / steps);
        outwardSteps = returnSteps = steps;
        lungeUsed = true;
        lungeAge = serverWait = 0;
        advanceLunge(mc, weapon);
    }

    private void advanceLunge(Minecraft mc, KineticWeapon weapon) {
        if (!integratedHostReady(mc) || !mc.options.keyAttack.isDown() || !validTarget(mc, target)
            || ++lungeAge > 64 || !HeldSlotManager.holds(this)) { clearLunge(); return; }
        if (outwardSteps > 0 && SpecializedCombatDecisions.chargeWindow(mc.player.getTicksUsingItem(), weapon.delayTicks(),
                weapon.computeDamageUseDuration()) == ChargeWindow.SPENT) beginReturn();
        if (queuedMove.lengthSqr() > 0) {
            if (mc.player.tickCount - queuedTick > 2) clearLunge();
            return;
        }
        Vec3 next = outwardSteps > 0 ? lungeStep : returnSteps > 0 ? lungeStep.scale(-1) : Vec3.ZERO;
        if (serverSample == null) {
            UUID playerId = mc.player.getUUID();
            UUID targetId = target.getUUID();
            var dimension = mc.level.dimension();
            boolean returning = outwardSteps == 0;
            var server = mc.getSingleplayerServer();
            serverSample = server.submit(() -> {
                ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                ServerPlayer enemy = server.getPlayerList().getPlayer(targetId);
                if (player == null) return new ServerSample(Vec3.ZERO, false);
                KineticWeapon nativeWeapon = player.getUseItem().get(DataComponents.KINETIC_WEAPON);
                AABB swept = player.getBoundingBox().move(0, 0.02, 0).expandTowards(next).deflate(1.0e-5);
                boolean allowed = server.isRunning() && player.level().dimension().equals(dimension)
                    && player.isAlive() && !player.isSpectator() && player.onGround() && !player.isPassenger()
                    && player.isUsingItem() && nativeWeapon != null && enemy != null && enemy.isAlive()
                    && enemy.level() == player.level() && !enemy.isSpectator()
                    && (returning || SpecializedCombatDecisions.chargeWindow(player.getTicksUsingItem(), nativeWeapon.delayTicks(),
                        nativeWeapon.computeDamageUseDuration()) == ChargeWindow.ACTIVE)
                    && player.level().getWorldBorder().isWithinBounds(swept) && player.level().noCollision(player, swept)
                    && !player.level().containsAnyLiquid(swept)
                    && !player.level().noCollision(player, player.getBoundingBox().move(next).move(0, -0.12, 0));
                return new ServerSample(player.position(), allowed);
            });
            status = "Experimental: awaiting server";
            return;
        }
        if (!serverSample.isDone()) {
            if (++serverWait > SERVER_WAIT_TICKS) clearLunge();
            return;
        }
        ServerSample sample;
        try { sample = serverSample.join(); }
        catch (RuntimeException ex) { clearLunge(); return; }
        serverSample = null;
        Vec3 actual = sample.position();
        Vec3 expected = expectedServerPosition == null ? mc.player.position() : expectedServerPosition;
        if (!sample.allowed()) { clearLunge(); return; }
        if (!SpecializedCombatDecisions.sameHorizontalPosition(actual.x, actual.z, expected.x, expected.z, 0.2)
            || Math.abs(actual.y - expected.y) > 0.35) {
            if (++serverWait > SERVER_WAIT_TICKS) clearLunge();
            return;
        }
        expectedServerPosition = null;
        serverWait = 0;
        if (outwardSteps == 0 && returnSteps == 0) { clearLunge(); status = "Experimental: return acknowledged"; return; }
        queuedMove = next;
        queuedTick = mc.player.tickCount;
    }

    /** Called once per native SELF move by BossSpearAssistMovementMixin; never writes deltaMovement. */
    public Vec3 additionalMovement(Minecraft mc, MoverType type, Vec3 movement) {
        if (type != MoverType.SELF || queuedMove.lengthSqr() == 0 || !ready(mc) || mc.player != contextPlayer
            || mc.level != contextLevel || !integratedHostReady(mc) || target == null || !validTarget(mc, target)
            || !mc.player.isUsingItem() || !mc.options.keyUse.isDown() || !mc.options.keyAttack.isDown()
            || !HeldSlotManager.holds(this) || lastMoveTick == mc.player.tickCount) return movement;
        KineticWeapon weapon = mc.player.getUseItem().get(DataComponents.KINETIC_WEAPON);
        if (weapon == null || mc.player.tickCount - queuedTick > 2) { clearLunge(); return movement; }
        if (outwardSteps > 0 && SpecializedCombatDecisions.chargeWindow(mc.player.getTicksUsingItem(), weapon.delayTicks(),
            weapon.computeDamageUseDuration()) != ChargeWindow.ACTIVE) { beginReturn(); return movement; }
        Vec3 extra = queuedMove;
        AABB sweep = mc.player.getBoundingBox().move(0, 0.02, 0).expandTowards(extra).deflate(1.0e-5);
        if (!mc.level.noCollision(mc.player, sweep) || !mc.level.getWorldBorder().isWithinBounds(sweep)
            || mc.level.containsAnyLiquid(sweep)
            || mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(extra).move(0, -0.12, 0))
            || protectedRay(mc, mc.player.getEyePosition(), mc.player.getLookAngle(), decimal("range"), 0.3)) {
            clearLunge();
            return movement;
        }
        queuedMove = Vec3.ZERO;
        lastMoveTick = mc.player.tickCount;
        if (outwardSteps > 0) outwardSteps--; else returnSteps--;
        expectedServerPosition = mc.player.position().add(movement).add(extra);
        return movement.add(extra);
    }

    private boolean protectedRay(Minecraft mc, Vec3 eye, Vec3 direction, double length, double margin) {
        Vec3 end = eye.add(direction.scale(length));
        for (Player player : mc.level.players()) {
            if (player != mc.player && !player.isSpectator() && PvpUtil.isProtectedPlayer(mc, player)
                && player.getBoundingBox().inflate(Math.max(0.1, margin)).clip(eye, end).isPresent()) return true;
        }
        return false;
    }

    public void onServerCorrection() {
        if (lungeStep.lengthSqr() > 0) status = "Experimental: stopped on server correction";
        clearLunge();
    }

    private void beginReturn() {
        returnSteps -= outwardSteps;
        outwardSteps = 0;
        queuedMove = Vec3.ZERO;
        if (serverSample != null) serverSample.cancel(false);
        serverSample = null;
        serverWait = 0;
    }

    private void clearLunge() {
        if (serverSample != null) serverSample.cancel(false);
        serverSample = null;
        lungeStep = queuedMove = Vec3.ZERO;
        expectedServerPosition = null;
        outwardSteps = returnSteps = lungeAge = serverWait = 0;
    }

    private static boolean sameRotation(RotationUtil.Rotation a, RotationUtil.Rotation b) {
        return Math.abs(Mth.wrapDegrees(a.yaw() - b.yaw())) < 0.001f && Math.abs(a.pitch() - b.pitch()) < 0.001f;
    }

    private void reset(Minecraft mc) {
        if (mc != null && mc.player != null && mc.player == contextPlayer && mc.level == contextLevel) {
            if (mc.gameMode != null && HeldSlotManager.holds(this) && !ownedUse.isEmpty()
                && mc.player.isUsingItem() && mc.player.getUseItem() == ownedUse && mc.player.getUsedItemHand() == ownedHand
                && mc.player.getTicksUsingItem() >= lastUseTicks) mc.gameMode.releaseUsingItem(mc.player);
            if (beforeAim != null && lastAim != null && sameRotation(RotationUtil.playerRotation(mc.player), lastAim)) {
                RotationUtil.apply(mc.player, beforeAim, false);
            }
        }
        clearLunge();
        HeldSlotManager.clear(this);
        target = null;
        contextPlayer = null;
        contextLevel = null;
        ownedUse = restartStack = ItemStack.EMPTY;
        ownedHand = restartHand = null;
        lastUseTicks = -1;
        aimSubmitted = lungeUsed = false;
        lastMoveTick = Integer.MIN_VALUE;
        beforeAim = lastAim = null;
        status = "Idle";
    }
}
