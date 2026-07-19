package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.RetaliationTracker;
import com.boss.pvp.util.pvp.RotationManager;
import com.boss.pvp.util.pvp.PlayerSimulation;
import com.boss.pvp.util.pvp.Gcd;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismBindUtil;
import autismclient.util.AutismInputGate;
import autismclient.util.AutismRotationUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class KillAuraModule extends Module {

    private long lastAttackMs = 0L;
    private long nextDelayMs = 0L;
    private LivingEntity currentTarget;
    private LivingEntity lockedTarget;   // committed target, persists across ticks (see pickPrimary)
    private boolean autoBlocking = false;

    // Smart-sort threat weights (lower total score = higher priority).
    private static final double W_DIST = 4.0, W_HP = 1.0, W_ANGLE = 0.1, W_HURT = 3.0;

    private float aimYaw, aimPitch;
    private boolean haveAim = false;
    private boolean rotationArrived = true;
    private boolean butterflyFast = false;
    private final java.util.Random rng = new java.util.Random();

    // Retaliation mode: which mobs have hit the player recently (see updateRetaliation / collectTargets).
    private final RetaliationTracker retaliation = new RetaliationTracker();
    private int prevHurtTime = 0;

    public KillAuraModule() {
        super(BossPvpAddon.ID + ":killaura", "KillAura", "Melee kill aura with silent rotation, prediction and autoblock.");

        add(RegistryListSetting.entityTypes("entities", "Entities", PvpUtil.DEFAULT_COMBAT_TARGETS));
        add(new DoubleSetting("range", "Range", 3.0, 1.0, 3.5, 0.1)
            .description("Attack reach. Vanilla is 3.0; above is opt-in and may flag on strict anticheats."));
        add(new DoubleSetting("targetRange", "Target range", 3.0, 1.0, 6.0, 0.1)
            .description("How far to look for / aim at a target. The hit still only lands within Range."));
        add(new IntSetting("fov", "FOV", 180, 0, 180, 1));
        add(new IntSetting("hurtTime", "Hurt Time", 10, 0, 10, 1));
        add(new BoolSetting("swing", "Swing", true));
        add(new BoolSetting("raytrace", "Raytrace (only what you see)", true));

        add(new IntSetting("minCps", "Min CPS", 8, 1, 20, 1).group("Attack"));
        add(new IntSetting("maxCps", "Max CPS", 12, 1, 20, 1).group("Attack"));
        add(new ChoiceSetting("clickPattern", "Click pattern", "Stabilized", "Stabilized", "NormalDistribution", "Butterfly", "Drag")
            .description("Human-like attack timing. Stabilized = the previous even CPS timing.").group("Attack"));
        add(new BoolSetting("noMissCooldown", "No-miss cooldown", false)
            .description("Don't spend the attack cooldown on a tick where no target is in reach.").group("Attack"));
        add(new BoolSetting("fullCharge", "Full charge only", true).group("Attack"));
        add(new IntSetting("hitChance", "Hit chance", 100, 0, 100, 1).formatter(v -> v + "%").group("Attack"));

        add(new ChoiceSetting("activationMode", "Activation", "Always", "Always", "Retaliation")
            .description("Retaliation = only target a mob AFTER it has attacked you (kept in the pool for the "
                + "memory window so it doesn't drop mid-fight). Players are unaffected. Always = current behavior.")
            .group("Targeting"));
        add(new IntSetting("retaliationMemory", "Retaliation memory", 10, 1, 60, 1)
            .formatter(v -> v + "s")
            .description("How long an attacking mob stays targetable after its last hit on you.")
            .visibleWhen(() -> "Retaliation".equals(choice("activationMode")))
            .group("Targeting"));
        add(new ChoiceSetting("rotationMode", "Rotation", "Silent", "Silent", "Real", "None").group("Targeting"));
        add(new IntSetting("rotationSpeed", "Rotation speed", 180, 1, 180, 1)
            .formatter(v -> v + "°/t")
            .description("Max degrees the aim turns per tick toward the target. 180 = instant snap; lower = smooth, legit glide (attack waits until aligned).").group("Targeting"));
        add(new BoolSetting("smoothAim", "Smooth aim (ease-out)", false)
            .description("Human accel/decel rotation curve toward the target (Rotation speed is the cap) instead of a constant per-tick step. Off = the previous linear glide.").group("Targeting"));
        add(new ChoiceSetting("targetSort", "Sort", "Smart", "Smart", "Distance", "Health", "HurtTime", "Age", "Direction", "Angle", "Type", "LowestHpThenDistance")
            .description("Smart = weighted threat priority (closeness + low effective HP incl. armour + in-view + hittable-now).").group("Targeting"));
        add(new ChoiceSetting("secondarySort", "Secondary sort", "None", "None", "Distance", "Health", "HurtTime", "Age", "Direction").group("Targeting"));
        add(new ChoiceSetting("rotationTiming", "Rotation timing", "Always", "Always", "OnTick")
            .description("OnTick = only rotate on the tick we actually attack (look freely between hits).").group("Targeting"));
        add(new BoolSetting("gcd", "GCD (legit rotations)", true)
            .description("Snap sent rotations onto the mouse-sensitivity grid so aim looks mouse-produced (defeats rotation-analysis anti-cheat). Off = raw rotations.").group("Targeting"));
        add(new BoolSetting("ignoreOnShieldBreak", "Ignore cooldown on shield-break", false)
            .description("Skip the CPS delay while ShieldBreaker is breaking the current target.").group("Targeting"));
        add(new IntSetting("maxTargets", "Max targets", 1, 1, 5, 1).group("Targeting"));
        add(new BoolSetting("targetLock", "Target commitment", true)
            .description("Commit to the current target across ticks; only switch when it dies/leaves reach or another scores clearly better. Stops aim flip-flopping between similar targets.").group("Targeting"));
        add(new DoubleSetting("switchMargin", "Switch margin", 0.20, 0.0, 1.0, 0.05)
            .description("How much better (relative) a new target must score before commitment switches to it. Higher = stickier.")
            .visibleWhen(() -> bool("targetLock")).group("Targeting"));
        add(new BoolSetting("rotateOnly", "Rotate only (no auto-attack)", false).group("Targeting"));
        add(new ChoiceSetting("aimPart", "Aim at", "Body", "Body", "Head", "Feet", "Nearest").group("Targeting"));
        add(new BoolSetting("prediction", "Prediction", true).group("Targeting"));
        add(new DoubleSetting("predictionStrength", "Prediction strength", 0.5, 0.0, 3.0, 0.1).group("Targeting"));
        add(new BoolSetting("onlyWeapon", "Only while holding weapon", false).group("Targeting"));
        add(new BoolSetting("pauseEat", "Pause while eating", true).group("Targeting"));
        add(new BoolSetting("pauseMine", "Pause while mining", true).group("Targeting"));
        add(new BoolSetting("keepSprint", "Keep sprint on hit", true).group("Targeting"));
        add(new BoolSetting("physicsPredict", "Physics prediction", false)
            .description("Also allow a hit when the target's predicted 1-tick position is within reach (additive, off = unchanged).").group("Targeting"));
        add(new BoolSetting("sprintReset", "Sprint reset (crit W-tap)", false)
            .description("Stop-sprint right before the hit so it can land as a critical (vanilla blocks crits while sprinting). keepSprint re-sprints after.").group("Targeting"));

        add(new BoolSetting("autoBlock", "Auto block", false).group("AutoBlock"));

        add(new BoolSetting("teamCheck", "Team check", false)
            .description("Skip players wearing leather armour dyed the same colour as yours (teammates).").group("Team"));
        add(new StringListSetting("friends", "Friends list", java.util.List.<String>of())
            .description("Whitelisted player names, case-insensitive. Skipped by all combat modules whenever this list has entries.").group("Team"));
        add(new KeybindSetting("addFriendKey", "Add target to friends", -1)
            .description("Adds your current KillAura or crosshair target to the friends list.").group("Team"));
    }

    private boolean prevAddFriendDown = false;

    public java.util.List<String> friends() { return java.util.Collections.unmodifiableList(list("friends")); }

    @Override
    public void onDisable() {
        currentTarget = null;
        lockedTarget = null;
        haveAim = false;
        retaliation.clear();
        prevHurtTime = 0;

        if (autoBlocking) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameMode != null && mc.player != null) mc.gameMode.releaseUsingItem(mc.player);
            autoBlocking = false;
        }
    }

    public LivingEntity currentTarget() { return currentTarget; }

    public long lastAttackMs() { return lastAttackMs; }

    public void tick(Minecraft mc) {
        currentTarget = null;
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null) { haveAim = false; return; }

        // Record incoming mob hits BEFORE any pause/defer early-outs so Retaliation memory doesn't miss
        // hits taken while eating, mining, or during a crystal cycle.
        updateRetaliation(p);

        if (com.boss.pvp.util.CombatManager.isCombatPaused()) { haveAim = false; return; }

        // Give AutoCrystal exclusive control of the hotbar slot during its place->break cycle so the two
        // don't rapidly swap the held item (the "spaz"). AutoCrystal clears this when its cycle ends.
        if (BossPvpAddon.crystalActive) { haveAim = false; return; }

        if (bool("onlyWeapon") && !isWeapon(p.getMainHandItem())) { haveAim = false; return; }
        if (bool("pauseEat") && p.isUsingItem() && !autoBlocking) return;
        if (bool("pauseMine") && mc.gameMode != null && mc.gameMode.isDestroying()) return;

        Set<String> ids = PvpUtil.entityIds(list("entities"));
        // When Reach is enabled, use its (extended) attack range so the gate matches the reach the
        // player actually has, instead of KillAura's own 3.5-capped option. Otherwise use our own range.
        double range = (BossPvpAddon.reach != null && BossPvpAddon.reach.isEnabled())
            ? BossPvpAddon.reach.getAttackRange()
            : decimal("range");
        List<LivingEntity> targets = collectTargets(mc, p, decimal("targetRange"), ids);
        if (targets.isEmpty()) { haveAim = false; lockedTarget = null; return; }

        LivingEntity primary = pickPrimary(p, targets);
        currentTarget = primary;

        Vec3 eyes = p.getEyePosition();
        Vec3 point = aimPoint(primary, eyes);
        AutismRotationUtil.Rotation wanted = AutismRotationUtil.lookingAt(point, eyes);

        boolean onTick = "OnTick".equals(choice("rotationTiming"));
        if (!onTick) {
            applyRotation(p, wanted);
            if (bool("rotateOnly")) return;
            if (!rotationArrived) { autoBlock(mc, p, true); return; }
        } else {
            haveAim = false;
            if (bool("rotateOnly")) { applyRotation(p, wanted); return; }
        }

        boolean shieldBreakBypass = bool("ignoreOnShieldBreak")
            && BossPvpAddon.shieldBreaker != null && BossPvpAddon.shieldBreaker.isActive()
            && BossPvpAddon.shieldBreaker.victim() == primary;

        long now = System.currentTimeMillis();
        if (!shieldBreakBypass && now - lastAttackMs < nextDelayMs) { autoBlock(mc, p, false); return; }
        if (bool("fullCharge")) {
            // Gate on the weapon AutoWeapon will swap to on this same tick (below), not the currently held item —
            // otherwise a sword->axe swap slips an under-charged swing past a current-item charge check.
            boolean charged = (BossPvpAddon.autoWeapon != null && BossPvpAddon.autoWeapon.isEnabled())
                ? BossPvpAddon.autoWeapon.chargeReadyForBestWeapon(primary)
                : PvpUtil.fullCharge(p);
            if (!charged) { autoBlock(mc, p, true); return; }
        }
        if (!PvpUtil.roll(integer("hitChance"))) { lastAttackMs = now; nextDelayMs = patternDelay(); return; }

        if (autoBlocking) { mc.gameMode.releaseUsingItem(p); autoBlocking = false; }

        boolean wasSprinting = p.isSprinting();

        if (bool("sprintReset") && wasSprinting) {
            var conn = mc.getConnection();
            if (conn != null) conn.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            p.setSprinting(false);
        }

        if (BossPvpAddon.autoWeapon != null && BossPvpAddon.autoWeapon.isEnabled()) BossPvpAddon.autoWeapon.selectBestWeapon(primary);

        if (onTick) {
            AutismRotationUtil.Rotation snap = bool("gcd")
                ? Gcd.normalize(AutismRotationUtil.playerRotation(p), wanted)
                : wanted;
            var conn = mc.getConnection();
            if (conn != null) conn.send(new ServerboundMovePlayerPacket.Rot(snap.yaw(), snap.pitch(), p.onGround(), p.horizontalCollision));
        }

        double atkSq = range * range;
        boolean physPredict = bool("physicsPredict");
        boolean hit = false;
        for (LivingEntity target : targets) {
            boolean inReach = target.distanceToSqr(p) <= atkSq;

            if (!inReach && physPredict && target instanceof Player pt) {
                inReach = PlayerSimulation.predictPosition(pt, 1).distanceToSqr(p.position()) <= atkSq;
            }
            if (!inReach) continue;
            mc.gameMode.attack(p, target);
            if (bool("swing")) p.swing(InteractionHand.MAIN_HAND);
            hit = true;
        }
        if (bool("keepSprint") && wasSprinting) p.setSprinting(true);

        if (hit || !bool("noMissCooldown")) {
            lastAttackMs = now;
            nextDelayMs = patternDelay();
        }
    }

    private long patternDelay() {
        int min = integer("minCps");
        int max = integer("maxCps");
        return switch (choice("clickPattern")) {
            case "NormalDistribution" -> {
                double mean = (min + max) / 2.0;
                double cps = mean + rng.nextGaussian() * Math.max(0.5, (max - min) / 4.0);
                cps = Math.max(1.0, Math.min(20.0, cps));
                yield (long) (1000.0 / cps);
            }
            case "Butterfly" -> {
                butterflyFast = !butterflyFast;
                int cps = butterflyFast ? max : min;
                yield (long) (1000.0 / Math.max(1, cps));
            }
            case "Drag" -> (long) (1000.0 / Math.max(1, max * 2));
            default -> PvpUtil.cpsDelayMs(min, max);
        };
    }

    /**
     * Retaliation memory upkeep, called every tick. A fresh hurt is detected by {@code hurtTime} rising
     * versus last tick (a new hit resets it to ~10); attribution comes from {@code getLastHurtByMob()},
     * which the client populates from the damage-event packet's attacker id. Only mobs are recorded —
     * Retaliation never restricts (or tracks) players.
     */
    private void updateRetaliation(LocalPlayer p) {
        boolean freshHurt = p.hurtTime > prevHurtTime;
        prevHurtTime = p.hurtTime;
        if (!freshHurt) return;
        LivingEntity attacker = p.getLastHurtByMob();
        if (attacker == null || attacker instanceof Player || attacker.isRemoved()) return;
        long now = System.currentTimeMillis();
        retaliation.record(attacker.getId(), now);
        retaliation.prune(now, retaliationWindowMs());   // piggyback cleanup on the (rare) hurt event
    }

    private long retaliationWindowMs() {
        return integer("retaliationMemory") * 1000L;
    }

    private List<LivingEntity> collectTargets(Minecraft mc, LocalPlayer p, double range, Set<String> ids) {
        boolean los = bool("raytrace");
        double fov = integer("fov");
        int hurtCap = integer("hurtTime");
        String sort = choice("targetSort");
        List<LivingEntity> out = new ArrayList<>();

        int max = integer("maxTargets");
        double rangeSq = range * range;
        List<LivingEntity> all = new ArrayList<>();

        boolean retaliationMode = "Retaliation".equals(choice("activationMode"));
        long nowMs = System.currentTimeMillis();
        long windowMs = retaliationWindowMs();

        AABB area = p.getBoundingBox().inflate(range);
        for (LivingEntity living : mc.level.getEntitiesOfClass(LivingEntity.class, area,
                e -> e != p && !e.isRemoved() && e.isAlive())) {
            if (living.hurtTime > hurtCap) continue;
            if (!PvpUtil.matchesEntity(living, ids)) continue;
            if (living instanceof Player pl && (PvpUtil.isFriend(pl, friends())
                    || (bool("teamCheck") && PvpUtil.isTeammate(p, pl)))) continue;
            if (!RetaliationTracker.shouldTarget(retaliationMode, living instanceof Player,
                    retaliation.isActive(living.getId(), nowMs, windowMs))) continue;
            if (living.distanceToSqr(p) > rangeSq) continue;
            if (fov < 180.0 && PvpUtil.angleTo(p, living) > fov) continue;
            if (los && !PvpUtil.canSeeEntity(mc, p, living)) continue;
            all.add(living);
        }
        String secondary = choice("secondarySort");
        all.sort((a, b) -> {
            int c = Double.compare(score(p, a, sort), score(p, b, sort));
            if (c != 0 || "None".equals(secondary)) return c;
            return Double.compare(score(p, a, secondary), score(p, b, secondary));
        });
        for (int i = 0; i < all.size() && i < max; i++) out.add(all.get(i));
        return out;
    }

    private double score(LocalPlayer p, LivingEntity e, String sort) {
        return switch (sort) {
            case "Smart" -> smartScore(p, e);
            case "Health" -> e.getHealth() + e.getAbsorptionAmount();
            case "Angle", "Direction" -> PvpUtil.angleTo(p, e);
            case "HurtTime" -> e.hurtTime;
            case "Age" -> e.tickCount;

            case "Type" -> typeWeight(e) * 1.0e6 + Math.sqrt(e.distanceToSqr(p));
            case "LowestHpThenDistance" -> (e.getHealth() + e.getAbsorptionAmount()) * 10000.0 + Math.sqrt(e.distanceToSqr(p));
            default -> e.distanceToSqr(p);
        };
    }

    // Weighted threat priority (lower = focus first): closeness, low effective HP (armour makes a target
    // tankier so it ranks lower-priority), how far off-crosshair it is, and whether it's hittable right now
    // (hurtTime = temporary invulnerability after a hit). This is how a real player picks who to burst down.
    private double smartScore(LocalPlayer p, LivingEntity e) {
        double dist = Math.sqrt(e.distanceToSqr(p));
        double effHp = e.getHealth() + e.getAbsorptionAmount() + e.getArmorValue() * 0.5;
        double angle = PvpUtil.angleTo(p, e);
        return smartScoreOf(dist, effHp, angle, e.hurtTime);
    }

    /** Pure weighting for {@link #smartScore} (unit-tested). Lower total = higher priority. */
    public static double smartScoreOf(double dist, double effHp, double angle, double hurt) {
        return dist * W_DIST + effHp * W_HP + angle * W_ANGLE + hurt * W_HURT;
    }

    // Target commitment: keep the locked target across ticks (no flip-flopping) while it's still a valid
    // candidate, and only switch when a fresh candidate scores meaningfully better (relative switch margin).
    private LivingEntity pickPrimary(LocalPlayer p, List<LivingEntity> targets) {
        LivingEntity best = targets.get(0);
        if (!bool("targetLock")) { lockedTarget = best; return best; }

        if (lockedTarget != null && targets.contains(lockedTarget)) {
            if (best != lockedTarget) {
                String sort = choice("targetSort");
                double bestScore = score(p, best, sort);
                double lockScore = score(p, lockedTarget, sort);
                double margin = decimal("switchMargin");
                // all sorts are "lower = better"; require a clear relative improvement to switch
                if (bestScore < lockScore - Math.abs(lockScore) * margin) lockedTarget = best;
            }
        } else {
            lockedTarget = best;
        }
        return lockedTarget;
    }

    private int typeWeight(LivingEntity e) {
        if (e instanceof Player) return 0;
        if (e instanceof Enemy) return 1;
        return 2;
    }

    private Vec3 aimPoint(LivingEntity target, Vec3 eyes) {
        Vec3 point = aimBase(target, eyes);
        if (bool("prediction")) {
            Vec3 v = target.getDeltaMovement();
            double s = decimal("predictionStrength");
            point = point.add(v.x * s, v.y * s, v.z * s);
        }
        return point;
    }

    private Vec3 aimBase(LivingEntity target, Vec3 eyes) {
        AABB box = target.getBoundingBox();
        return switch (choice("aimPart")) {
            case "Head" -> target.getEyePosition();
            case "Feet" -> new Vec3(box.getCenter().x, box.minY + 0.1, box.getCenter().z);
            case "Nearest" -> new Vec3(
                Mth.clamp(eyes.x, box.minX, box.maxX),
                Mth.clamp(eyes.y, box.minY, box.maxY),
                Mth.clamp(eyes.z, box.minZ, box.maxZ));
            default -> box.getCenter();
        };
    }

    private void applyRotation(LocalPlayer p, AutismRotationUtil.Rotation wanted) {
        String mode = choice("rotationMode");
        if ("None".equals(mode)) { rotationArrived = true; haveAim = false; return; }

        if (!haveAim) { aimYaw = p.getYRot(); aimPitch = p.getXRot(); haveAim = true; }
        float speed = integer("rotationSpeed");
        float dYaw = Mth.wrapDegrees(wanted.yaw() - aimYaw);
        float dPitch = wanted.pitch() - aimPitch;
        float stepYaw, stepPitch;
        if (bool("smoothAim")) {
            stepYaw = easeStep(dYaw, speed);
            stepPitch = easeStep(dPitch, speed);
        } else {
            stepYaw = Mth.clamp(dYaw, -speed, speed);
            stepPitch = Mth.clamp(dPitch, -speed, speed);
        }
        aimYaw = Mth.wrapDegrees(aimYaw + stepYaw);
        aimPitch = Mth.clamp(aimPitch + stepPitch, -90.0f, 90.0f);
        rotationArrived = Math.abs(Mth.wrapDegrees(wanted.yaw() - aimYaw)) <= 2.0f
                       && Math.abs(wanted.pitch() - aimPitch) <= 2.0f;

        boolean silent = !"Real".equals(mode);
        boolean gcd = bool("gcd");
        RotationManager.submit(aimYaw, aimPitch, RotationManager.PRIORITY_KILLAURA, silent, gcd);
        if (!silent) {
            AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(p);
            AutismRotationUtil.Rotation want = new AutismRotationUtil.Rotation(aimYaw, aimPitch);
            AutismRotationUtil.apply(p, gcd ? Gcd.normalize(cur, want) : want, false);
        }
    }

    // Human ease-out step: proportional to the remaining angle (accelerate far, decelerate near), capped at
    // maxStep, with a 1° floor so it converges instead of crawling forever near the target.
    private float easeStep(float delta, float maxStep) {
        float step = Mth.clamp(delta * 0.45f, -maxStep, maxStep);
        if (Math.abs(step) < 1.0f && delta != 0f) step = Math.signum(delta) * Math.min(1.0f, Math.abs(delta));
        return step;
    }

    private void autoBlock(Minecraft mc, LocalPlayer p, boolean betweenHits) {
        if (!bool("autoBlock") || !betweenHits || mc.gameMode == null) return;
        InteractionHand hand = shieldHand(p);
        if (hand == null) return;
        if (!p.isUsingItem()) { mc.gameMode.useItem(p, hand); autoBlocking = true; }
    }

    private InteractionHand shieldHand(LocalPlayer p) {
        if (p.getOffhandItem().is(Items.SHIELD)) return InteractionHand.OFF_HAND;
        if (p.getMainHandItem().is(Items.SHIELD)) return InteractionHand.MAIN_HAND;
        return null;
    }

    private boolean isWeapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES);
    }

    public void pollFriendKey(Minecraft mc) {
        int key = parseKey(value("addFriendKey"));
        if (key == -1 || !AutismInputGate.canRunAutismKeybinds()) { prevAddFriendDown = false; return; }
        boolean down = AutismBindUtil.isBindPressed(mc, key);
        if (down && !prevAddFriendDown) addCurrentTargetToFriends(mc);
        prevAddFriendDown = down;
    }

    private void addCurrentTargetToFriends(Minecraft mc) {
        Player target = null;
        if (mc.crosshairPickEntity instanceof Player cp) target = cp;
        else if (currentTarget instanceof Player pl) target = pl;
        if (target == null || target == mc.player) return;
        String name = target.getGameProfile().name();
        if (name == null || name.isBlank()) return;
        java.util.List<String> cur = new java.util.ArrayList<>(list("friends"));
        for (String f : cur) if (f != null && f.trim().equalsIgnoreCase(name)) return;
        cur.add(name);
        setValue("friends", String.join(",", cur));
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("[BossPVP] Added " + name + " to friends"));
    }

    private int parseKey(String s) {
        if (s == null) return -1;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }
}
