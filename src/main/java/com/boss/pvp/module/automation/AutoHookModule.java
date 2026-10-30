/*
 * Bobber lifecycle reference: LiquidBounce ModuleAutoRod, Copyright (c) 2015-2026 CCBlueX.
 * GPL-3.0-or-later; see the central third-party notices and license resources.
 */
package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.CombatManager;
import com.boss.pvp.util.InventoryUtil;
import com.boss.pvp.util.MenuMode;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.ProjectileMath;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.RotationManager;
import com.boss.pvp.util.pvp.RotationUtil;

import autismclient.modules.Module;
import autismclient.api.module.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

public final class AutoHookModule extends Module implements HeldSlotManager.Revocable {

    private float lastHealth = -1.0f;
    private final Deque<Long> hits = new ArrayDeque<>();
    private long consumedAttackMs;
    private long nextCastMs;
    private LocalPlayer trackedPlayer;
    private Level trackedLevel;

    private int prevSlot = -1;
    private int rodSlot = -1;
    private LocalPlayer castPlayer;
    private Level castLevel;
    private LivingEntity victim;
    private RodCycle cycle;

    public AutoHookModule() {
        super(BossPvpAddon.ID + ":autohook", "AutoHook", "Casts a fishing rod at attackers.");

        add(RegistryListSetting.entityTypes("entities", "Targets", "minecraft:player"));
        add(new IntSetting("hits", "Hits to trigger", 3, 1, 10, 1).group("Trigger"));
        add(new IntSetting("window", "Combo window (ms)", 1500, 100, 5000, 50).group("Trigger").visibleWhen(MenuMode::advanced));
        add(new IntSetting("cooldown", "Cooldown (ms)", 1000, 0, 5000, 50).group("Trigger").visibleWhen(MenuMode::advanced));
        add(new ChoiceSetting("triggerMode", "Trigger mode", "Combo", "Combo", "On-attack").group("Trigger").visibleWhen(MenuMode::advanced));

        add(new DoubleSetting("range", "Range", 4.0, 1.0, 6.0, 0.1).group("Targeting")
            .description("How close the attacker must be. The default matches normal reach; higher may be flagged by anticheat."));
        add(new ChoiceSetting("rotationMode", "Rotation", "Silent", "Silent", "Real", "None").group("Targeting").visibleWhen(MenuMode::advanced));
        add(new ChoiceSetting("targetPriority", "Target priority", "Closest", "Closest", "Lowest HP").group("Targeting").visibleWhen(MenuMode::advanced));
        add(new BoolSetting("raytrace", "Only targets you can see", true).group("Targeting").visibleWhen(MenuMode::advanced));

        add(new BoolSetting("reelAfterCast", "Reel after cast", true).group("Actions").visibleWhen(MenuMode::advanced));
        add(new BoolSetting("pullMode", "Pull (reel immediately)", false).group("Actions").visibleWhen(MenuMode::advanced));
        add(new BoolSetting("switchBack", "Switch back to previous item", true).group("Actions").visibleWhen(MenuMode::advanced));
    }

    @Override public void onLeaseRevoked() { finish(Minecraft.getInstance()); }

    @Override
    public void onEnable() {
        forget();
        Minecraft mc = Minecraft.getInstance();
        trackedPlayer = mc == null ? null : mc.player;
        trackedLevel = mc == null ? null : mc.level;
        lastHealth = trackedPlayer == null ? -1.0f : trackedPlayer.getHealth() + trackedPlayer.getAbsorptionAmount();
        hits.clear();
        consumedAttackMs = CombatManager.lastAttackMs();
        nextCastMs = 0L;
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (ownsRod(mc) && cycle != null && cycle.owns(mc.player.fishing) && cycle.canReel()
                    && mc.gameMode != null && mc.gui.screen() == null && !protectedHook(mc, mc.player.fishing)) {
                useRod(mc, null);
            }
        } finally {
            finish(mc);
            trackedPlayer = null;
            trackedLevel = null;
            lastHealth = -1.0f;
            hits.clear();
        }
    }

    @Override
    public void onGameLeft() {
        forget();
        trackedPlayer = null;
        trackedLevel = null;
        lastHealth = -1.0f;
        hits.clear();
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc == null ? null : mc.player;
        if (p == null || mc.level == null) {
            finish(mc);
            trackedPlayer = null;
            trackedLevel = null;
            hits.clear();
            lastHealth = -1.0f;
            return;
        }
        if (p != trackedPlayer || mc.level != trackedLevel) {
            finish(mc);
            trackedPlayer = p;
            trackedLevel = mc.level;
            lastHealth = p.getHealth() + p.getAbsorptionAmount();
            hits.clear();
        }
        long now = System.currentTimeMillis();
        trackCombo(p, now);
        if (mc.gameMode == null || mc.getConnection() == null || mc.gui.screen() != null
                || p.isUsingItem() || p.isSpectator() || CombatManager.isCombatPaused() || PvpUtil.hostEnabled("hide")) {
            finish(mc);
            return;
        }

        if (cycle != null) {
            if (!ownsRod(mc)) {
                finish(mc);
                return;
            }
            HeldSlotManager.request(this, HeldSlotManager.PRIORITY_AUTOHOOK);
            CombatManager.pauseCombat(1);
            FishingHook bobber = p.fishing;
            if (bobber != null && (bobber.isRemoved() || bobber.getPlayerOwner() != p)) bobber = null;
            boolean stalled = bobber != null && (bobber.onGround() || bobber.horizontalCollision
                || bobber.getDeltaMovement().lengthSqr() < 1.0e-6);
            RodCycle.Action action = cycle.update(bobber, now, bobber != null && bobber.getHookedIn() != null,
                protectedHook(mc, bobber), validTarget(mc, victim), stalled, bool("reelAfterCast"), bool("pullMode"));
            if (action == RodCycle.Action.FINISH) finish(mc);
            else if (action == RodCycle.Action.REEL) {
                boolean used = false;
                try {
                    used = useRod(mc, null);
                } finally {
                    if (!used) finish(mc);
                }
            }
            return;
        }

        if (now < nextCastMs || p.fishing != null || !shouldTrigger(now)) return;
        LivingEntity target = nearestEnemy(mc, p);
        if (!validTarget(mc, target)) return;
        int slot = findRod(p);
        if (slot < 0) return;
        RotationUtil.Rotation rotation = castRotation(mc, p, target);
        if (rotation == null) return;
        if (!HeldSlotManager.tryAcquire(this, HeldSlotManager.PRIORITY_AUTOHOOK)) {
            HeldSlotManager.clear(this);
            return;
        }
        prevSlot = p.getInventory().getSelectedSlot();
        rodSlot = slot;
        castPlayer = p;
        castLevel = mc.level;
        victim = target;
        boolean cast = false;
        try {
            InventoryUtil.selectHotbarSlot(mc, slot);
            if (!ownsRod(mc) || !useRod(mc, rotation)) {
                nextCastMs = now + 100L;
                return;
            }
            cast = true;
            cycle = new RodCycle(now);
            nextCastMs = now + Math.max(50L, integer("cooldown"));
            consumedAttackMs = CombatManager.lastAttackMs();
            hits.clear();
            CombatManager.pauseCombat(1);
        } finally {
            if (!cast) finish(mc);
        }
    }

    private boolean ownsRod(Minecraft mc) {
        return mc != null && mc.player != null && mc.player == castPlayer && mc.level == castLevel
            && HeldSlotManager.holds(this) && mc.player.getInventory().getSelectedSlot() == rodSlot
            && mc.player.getMainHandItem().is(Items.FISHING_ROD);
    }

    private boolean useRod(Minecraft mc, RotationUtil.Rotation rotation) {
        LocalPlayer p = mc.player;
        if (!ownsRod(mc) || p.getCooldowns().isOnCooldown(p.getMainHandItem())) return false;
        RotationUtil.Rotation previous = RotationUtil.playerRotation(p);
        float previousYaw = p.yRotO;
        float previousPitch = p.xRotO;
        boolean rotate = rotation != null && !"None".equals(choice("rotationMode"));
        boolean real = "Real".equals(choice("rotationMode"));
        try {
            if (rotate) {
                RotationManager.submit(rotation.yaw(), rotation.pitch(), RotationManager.PRIORITY_PLACE, !real);
                // useItem serializes the player's current angles into ServerboundUseItemPacket, not RotationManager's next tick.
                RotationUtil.apply(p, rotation, false);
            }
            boolean used = mc.gameMode.useItem(p, InteractionHand.MAIN_HAND).consumesAction();
            if (used) p.swing(InteractionHand.MAIN_HAND);
            return used;
        } finally {
            if (rotate && !real) {
                RotationUtil.apply(p, previous, false);
                p.yRotO = previousYaw;
                p.xRotO = previousPitch;
            }
        }
    }

    private RotationUtil.Rotation castRotation(Minecraft mc, LocalPlayer p, LivingEntity target) {
        Vec3 point = target.getBoundingBox().getCenter();
        float yaw = RotationUtil.lookingAt(point, p.getEyePosition()).yaw();
        ProjectileMath.Shot shot = null;
        for (int i = 0; i < 2; i++) {
            Vec3 origin = ProjectileMath.origin(ProjectileMath.Kind.FISHING_ROD, p.getEyePosition(), yaw);
            shot = ProjectileMath.solve(ProjectileMath.Kind.FISHING_ROD, origin, Vec3.ZERO,
                point, target.getKnownMovement(), 30);
            if (shot == null) return null;
            yaw = shot.yaw();
        }
        RotationUtil.Rotation current = RotationUtil.playerRotation(p);
        RotationUtil.Rotation rotation = "None".equals(choice("rotationMode")) ? current
            : RotationUtil.normalizeToSensitivity(new RotationUtil.Rotation(shot.yaw(), shot.pitch()), current);
        Vec3 origin = ProjectileMath.origin(ProjectileMath.Kind.FISHING_ROD, p.getEyePosition(), rotation.yaw());
        Vec3 velocity = ProjectileMath.launchVelocity(ProjectileMath.Kind.FISHING_ROD, rotation.yaw(), rotation.pitch(), Vec3.ZERO);
        return ProjectileMath.clearShot(mc.level, p, target, ProjectileMath.Kind.FISHING_ROD,
            origin, velocity, Math.min(30, (int) Math.ceil(shot.ticks()) + 2)) ? rotation : null;
    }

    private void finish(Minecraft mc) {
        try {
            if (ownsRod(mc) && bool("switchBack") && prevSlot >= 0 && prevSlot < 9) {
                InventoryUtil.selectHotbarSlot(mc, prevSlot);
            }
        } finally {
            forget();
        }
    }

    private void forget() {
        prevSlot = -1;
        rodSlot = -1;
        castPlayer = null;
        castLevel = null;
        victim = null;
        cycle = null;
        HeldSlotManager.clear(this);
    }

    private void trackCombo(LocalPlayer p, long now) {
        float hp = p.getHealth() + p.getAbsorptionAmount();
        if (RodCycle.lostHealth(lastHealth, hp, p.hurtTime)) hits.addLast(now);
        lastHealth = hp;
        long cutoff = now - integer("window");
        while (!hits.isEmpty() && hits.peekFirst() < cutoff) hits.pollFirst();
    }

    private boolean shouldTrigger(long now) {
        if ("On-attack".equals(choice("triggerMode"))) {
            return RodCycle.newAttack(CombatManager.lastAttackMs(), consumedAttackMs, now, integer("window"));
        }
        return hits.size() >= integer("hits");
    }

    private boolean validTarget(Minecraft mc, LivingEntity target) {
        return target != null && target != mc.player && target.level() == mc.level && target.isAlive()
            && !target.isRemoved() && !target.isSpectator()
            && mc.player.distanceToSqr(target) <= decimal("range") * decimal("range")
            && PvpUtil.matchesEntity(target, PvpUtil.entityIds(list("entities")))
            && (!(target instanceof Player player) || !PvpUtil.isProtectedPlayer(mc, player))
            && (!bool("raytrace") || PvpUtil.canSeeEntity(mc, mc.player, target));
    }

    private LivingEntity nearestEnemy(Minecraft mc, LocalPlayer p) {
        if ("On-attack".equals(choice("triggerMode"))) {
            Entity attacked = CombatManager.currentTarget(mc);
            return attacked instanceof LivingEntity living && validTarget(mc, living) ? living : null;
        }
        Set<String> ids = PvpUtil.entityIds(list("entities"));
        String sort = "Lowest HP".equals(choice("targetPriority")) ? "Health" : "Distance";
        return PvpUtil.findTarget(mc, p, decimal("range"), 180.0, ids, bool("raytrace"), sort);
    }

    private int findRod(LocalPlayer p) {
        int selected = p.getInventory().getSelectedSlot();
        if (p.getMainHandItem().is(Items.FISHING_ROD) && !p.getCooldowns().isOnCooldown(p.getMainHandItem())) return selected;
        for (int i = 0; i < 9; i++) {
            if (p.getInventory().getItem(i).is(Items.FISHING_ROD) && !p.getCooldowns().isOnCooldown(p.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private static boolean protectedHook(Minecraft mc, FishingHook bobber) {
        return bobber != null && bobber.getHookedIn() instanceof Player player && PvpUtil.isProtectedPlayer(mc, player);
    }

    static final class RodCycle {
        enum Action { WAIT, REEL, FINISH }
        private final long castAt;
        private Object bobber;
        private long seenAt;
        private long reelAt = -1L;

        RodCycle(long castAt) {
            this.castAt = castAt;
        }

        boolean owns(Object observed) {
            return bobber != null && bobber == observed;
        }

        boolean canReel() {
            return reelAt < 0L;
        }

        Action update(Object observed, long now, boolean hooked, boolean protectedHook, boolean validTarget,
                      boolean stalled, boolean reelAfterCast, boolean pullMode) {
            if (now < castAt) return Action.FINISH;
            if (bobber == null) {
                if (observed == null) return now - castAt >= 1000L ? Action.FINISH : Action.WAIT;
                bobber = observed;
                seenAt = now;
            }
            if (observed != bobber || !reelAfterCast || protectedHook) return Action.FINISH;
            if (reelAt >= 0L) return now - reelAt >= 500L ? Action.FINISH : Action.WAIT;
            if (hooked || pullMode || !validTarget || now - seenAt >= 1500L || stalled && now - seenAt >= 150L) {
                reelAt = now;
                return Action.REEL;
            }
            return Action.WAIT;
        }

        static boolean newAttack(long attackMs, long consumedMs, long now, long maxAgeMs) {
            return attackMs > 0L && attackMs > consumedMs && now >= attackMs && now - attackMs <= maxAgeMs;
        }

        static boolean lostHealth(float before, float after, int hurtTime) {
            return before >= 0.0f && after < before - 0.01f && hurtTime > 0;
        }
    }
}
