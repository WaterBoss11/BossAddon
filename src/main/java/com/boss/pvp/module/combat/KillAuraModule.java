package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.RotationManager;
import com.boss.pvp.util.pvp.PlayerSimulation;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismRotationUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
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
    private boolean autoBlocking = false;

    private float aimYaw, aimPitch;
    private boolean haveAim = false;
    private boolean rotationArrived = true;

    public KillAuraModule() {
        super(BossPvpAddon.ID + ":killaura", "KillAura", "Melee kill aura with silent rotation, prediction and autoblock.");

        add(RegistryListSetting.entityTypes("entities", "Entities", "minecraft:player"));
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
        add(new BoolSetting("fullCharge", "Full charge only", true).group("Attack"));
        add(new IntSetting("hitChance", "Hit chance", 100, 0, 100, 1).formatter(v -> v + "%").group("Attack"));

        add(new ChoiceSetting("rotationMode", "Rotation", "Silent", "Silent", "Real", "None").group("Targeting"));
        add(new IntSetting("rotationSpeed", "Rotation speed", 180, 1, 180, 1)
            .formatter(v -> v + "°/t")
            .description("Max degrees the aim turns per tick toward the target. 180 = instant snap; lower = smooth, legit glide (attack waits until aligned).").group("Targeting"));
        add(new ChoiceSetting("targetSort", "Sort", "Distance", "Distance", "Health", "Angle", "Type", "LowestHpThenDistance").group("Targeting"));
        add(new IntSetting("maxTargets", "Max targets", 1, 1, 5, 1).group("Targeting"));
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
    }

    @Override
    public void onDisable() {
        currentTarget = null;
        haveAim = false;

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

        if (com.boss.pvp.util.CombatManager.isCombatPaused()) { haveAim = false; return; }

        if (bool("onlyWeapon") && !isWeapon(p.getMainHandItem())) { haveAim = false; return; }
        if (bool("pauseEat") && p.isUsingItem() && !autoBlocking) return;
        if (bool("pauseMine") && mc.gameMode != null && mc.gameMode.isDestroying()) return;

        Set<String> ids = PvpUtil.entityIds(list("entities"));
        double range = decimal("range");
        List<LivingEntity> targets = collectTargets(mc, p, decimal("targetRange"), ids);
        if (targets.isEmpty()) { haveAim = false; return; }

        LivingEntity primary = targets.get(0);
        currentTarget = primary;

        Vec3 eyes = p.getEyePosition();
        Vec3 point = aimPoint(primary, eyes);
        AutismRotationUtil.Rotation wanted = AutismRotationUtil.lookingAt(point, eyes);
        applyRotation(p, wanted);

        if (bool("rotateOnly")) return;
        if (!rotationArrived) { autoBlock(mc, p, true); return; }

        long now = System.currentTimeMillis();
        if (now - lastAttackMs < nextDelayMs) { autoBlock(mc, p, false); return; }
        if (bool("fullCharge") && !PvpUtil.fullCharge(p)) { autoBlock(mc, p, true); return; }
        if (!PvpUtil.roll(integer("hitChance"))) { lastAttackMs = now; nextDelayMs = PvpUtil.cpsDelayMs(integer("minCps"), integer("maxCps")); return; }

        if (autoBlocking) { mc.gameMode.releaseUsingItem(p); autoBlocking = false; }

        boolean wasSprinting = p.isSprinting();

        if (bool("sprintReset") && wasSprinting) {
            var conn = mc.getConnection();
            if (conn != null) conn.send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            p.setSprinting(false);
        }

        if (BossPvpAddon.autoWeapon != null && BossPvpAddon.autoWeapon.isEnabled()) BossPvpAddon.autoWeapon.selectBestWeapon(primary);

        double atkSq = range * range;
        boolean physPredict = bool("physicsPredict");
        for (LivingEntity target : targets) {
            boolean inReach = target.distanceToSqr(p) <= atkSq;

            if (!inReach && physPredict && target instanceof Player pt) {
                inReach = PlayerSimulation.predictPosition(pt, 1).distanceToSqr(p.position()) <= atkSq;
            }
            if (!inReach) continue;
            mc.gameMode.attack(p, target);
            if (bool("swing")) p.swing(InteractionHand.MAIN_HAND);
        }
        if (bool("keepSprint") && wasSprinting) p.setSprinting(true);

        lastAttackMs = now;
        nextDelayMs = PvpUtil.cpsDelayMs(integer("minCps"), integer("maxCps"));
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

        AABB area = p.getBoundingBox().inflate(range);
        for (LivingEntity living : mc.level.getEntitiesOfClass(LivingEntity.class, area,
                e -> e != p && !e.isRemoved() && e.isAlive())) {
            if (living.hurtTime > hurtCap) continue;
            if (!PvpUtil.matchesEntity(living, ids)) continue;
            if (bool("teamCheck") && living instanceof Player pl && PvpUtil.isTeammate(p, pl)) continue;
            if (living.distanceToSqr(p) > rangeSq) continue;
            if (fov < 180.0 && PvpUtil.angleTo(p, living) > fov) continue;
            if (los && !PvpUtil.canSeeEntity(mc, p, living)) continue;
            all.add(living);
        }
        all.sort((a, b) -> Double.compare(score(p, a, sort), score(p, b, sort)));
        for (int i = 0; i < all.size() && i < max; i++) out.add(all.get(i));
        return out;
    }

    private double score(LocalPlayer p, LivingEntity e, String sort) {
        return switch (sort) {
            case "Health" -> e.getHealth() + e.getAbsorptionAmount();
            case "Angle" -> PvpUtil.angleTo(p, e);

            case "Type" -> typeWeight(e) * 1.0e6 + Math.sqrt(e.distanceToSqr(p));
            case "LowestHpThenDistance" -> (e.getHealth() + e.getAbsorptionAmount()) * 10000.0 + Math.sqrt(e.distanceToSqr(p));
            default -> e.distanceToSqr(p);
        };
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
        aimYaw = Mth.wrapDegrees(aimYaw + Mth.clamp(dYaw, -speed, speed));
        aimPitch = Mth.clamp(aimPitch + Mth.clamp(dPitch, -speed, speed), -90.0f, 90.0f);
        rotationArrived = Math.abs(Mth.wrapDegrees(wanted.yaw() - aimYaw)) <= 2.0f
                       && Math.abs(wanted.pitch() - aimPitch) <= 2.0f;

        boolean silent = !"Real".equals(mode);
        RotationManager.submit(aimYaw, aimPitch, RotationManager.PRIORITY_KILLAURA, silent);
        if (!silent) {
            AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(p);
            AutismRotationUtil.apply(p, AutismRotationUtil.normalizeToSensitivity(
                new AutismRotationUtil.Rotation(aimYaw, aimPitch), cur), false);
        }
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
}
