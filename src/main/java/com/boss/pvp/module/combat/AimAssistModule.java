package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.PlayerSimulation;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismRotationUtil;
import autismclient.util.AutismUiScale;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AimAssistModule extends Module {

    private static final long STICKY_GRACE_MS = 400L;

    private static final double DECAY_MAX = 60.0;
    private static final double DECAY_MIN = 4.0;

    private UUID lockedId;
    private long lastSwitchMs = 0L;
    private long lockGraceUntil = 0L;

    private volatile boolean hasGlideTarget = false;
    private volatile float glideTargetYaw = 0.0f;
    private volatile float glideTargetPitch = 0.0f;
    private volatile boolean lockedOn = false;

    private long lastFrameNanos = 0L;

    private long aimCacheTick = Long.MIN_VALUE;
    private final Map<Entity, Vec3> aimPointCache = new HashMap<>();

    public AimAssistModule() {
        super(BossPvpAddon.ID + ":aimassist", "AimAssist",
            "Full AimAssist with per-frame smooth glide, plus sticky/prediction/LOS and an FOV circle.");

        add(RegistryListSetting.entityTypes("entities", "Entities", "minecraft:player"));
        add(new DoubleSetting("range", "Range", 4.2, 1.0, 8.0, 0.1));
        add(new IntSetting("fov", "FOV", 180, 0, 180, 1));
        add(new ChoiceSetting("target-point", "Target Point", "Center", "Nearest", "Center", "Head", "Body", "Feet"));
        add(new ChoiceSetting("priority", "Priority", "Direction", "Direction", "Type", "Health", "Distance", "HurtTime", "Age"));
        add(new ChoiceSetting("axis", "Axis", "Both", "Both", "Horizontal", "Vertical"));
        add(new IntSetting("hurt-time", "Hurt Time", 10, 0, 10, 1));

        add(new ChoiceSetting("smooth-mode", "Smooth", "Interpolation", "Interpolation", "Instant", "Linear", "Sigmoid").group("Smoothing"));
        add(new IntSetting("smoothness", "Smoothness", 35, 0, 100, 1)
            .visibleWhen(() -> usesSmoothing(this)).group("Smoothing"));
        add(new IntSetting("horizontal-speed", "Horizontal", 100, 1, 100, 1)
            .formatter(v -> v + "%").visibleWhen(() -> usesSmoothing(this)).group("Smoothing"));
        add(new IntSetting("vertical-speed", "Vertical", 100, 1, 100, 1)
            .formatter(v -> v + "%").visibleWhen(() -> usesSmoothing(this)).group("Smoothing"));

        add(new DoubleSetting("turnSpeed", "Turn speed (deg/s)", 420.0, 30.0, 1200.0, 10.0).group("Smoothing"));
        add(new DoubleSetting("sigSteepness", "Sigmoid steepness", 10.0, 0.0, 20.0, 0.5).group("Smoothing"));
        add(new DoubleSetting("sigMidpoint", "Sigmoid midpoint", 0.3, 0.0, 1.0, 0.05).group("Smoothing"));

        add(new BoolSetting("sticky", "Sticky target", true).group("Extras"));
        add(new IntSetting("switchDelay", "Switch delay (ms)", 400, 0, 2000, 10).group("Extras"));
        add(new BoolSetting("prediction", "Prediction", true).group("Extras"));
        add(new BoolSetting("physicsPredict", "Physics prediction", false)
            .description("Lead the aim by a 2-tick vanilla-physics movement prediction of the target (additive, off = unchanged).").group("Extras"));
        add(new DoubleSetting("predictionStrength", "Prediction strength", 1.0, 0.0, 3.0, 0.1).group("Extras"));
        add(new BoolSetting("requireLineOfSight", "Require line of sight", true).group("Extras"));
        add(new BoolSetting("onlyWhileAttacking", "Only while attacking", false).group("Extras"));

        add(new BoolSetting("showCircle", "Show FOV circle", true).group("Circle"));
        add(new BoolSetting("teamCheck", "Team check", false)
            .description("Skip players wearing leather armour dyed the same colour as yours (teammates).").group("Team"));
        add(new IntSetting("circleRadius", "Circle radius", 60, 10, 300, 1).group("Circle"));
        add(new IntSetting("circleThickness", "Circle thickness", 2, 1, 6, 1).group("Circle"));
        add(new BoolSetting("filled", "Filled", false).group("Circle"));
        add(new BoolSetting("releaseOnLeave", "Release when target leaves circle", false)
            .description("Also DROP a locked target the moment it leaves the circle (not just at acquisition). Off = sticky lock holds once acquired.")
            .group("Circle"));
    }

    public double circleCoreHalf() { return Math.max(1, integer("circleThickness")) / 2.0; }

    private static boolean usesSmoothing(Module module) {
        return !"Instant".equals(module.value("smooth-mode"));
    }

    @Override
    public void onDisable() {
        lockedId = null;
        lockGraceUntil = 0L;
        hasGlideTarget = false;
        lockedOn = false;
        lastFrameNanos = 0L;
        aimPointCache.clear();
        aimCacheTick = Long.MIN_VALUE;
    }

    public void tick(Minecraft mc) {
        lockedOn = false;
        LocalPlayer me = mc.player;
        if (me == null || mc.level == null || mc.gui.screen() != null) { resetState(); return; }
        if (bool("onlyWhileAttacking") && (mc.options == null || !mc.options.keyAttack.isDown())) { resetState(); return; }

        LivingEntity target = selectTarget(mc, me);
        if (target == null) { hasGlideTarget = false; return; }

        Vec3 eyes = me.getEyePosition();
        Vec3 point = aimPoint(mc, me, eyes, target);
        if (point == null) { hasGlideTarget = false; return; }

        if (bool("prediction")) {
            Vec3 vel = target.getDeltaMovement();
            double s = decimal("predictionStrength");
            point = point.add(vel.x * s, vel.y * s, vel.z * s);
        }

        if (bool("physicsPredict") && target instanceof Player pp) {
            point = point.add(PlayerSimulation.predictPosition(pp, 2).subtract(pp.position()));
        }

        AutismRotationUtil.Rotation wanted = AutismRotationUtil.lookingAt(point, eyes);
        glideTargetYaw = wanted.yaw();
        glideTargetPitch = wanted.pitch();
        hasGlideTarget = true;
        lockedOn = true;
    }

    private void resetState() {
        lockedId = null;
        lockGraceUntil = 0L;
        hasGlideTarget = false;
        lockedOn = false;
    }

    @Override
    public void onRenderLevel(float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null || mc.gui.screen() != null) {
            lastFrameNanos = 0L;
            return;
        }

        long now = System.nanoTime();
        long last = lastFrameNanos;
        lastFrameNanos = now;

        if (!hasGlideTarget) return;

        LocalPlayer p = mc.player;
        float targetYaw = glideTargetYaw;
        float targetPitch = Mth.clamp(glideTargetPitch, -90.0f, 90.0f);

        if ("Instant".equals(choice("smooth-mode"))) {
            applyRotation(p, targetYaw, targetPitch);
            return;
        }

        if (last == 0L) return;
        float dt = (float) ((now - last) / 1.0e9);
        if (dt <= 0.0f) return;
        if (dt > 0.1f) dt = 0.1f;

        float curYaw = p.getYRot();
        float curPitch = p.getXRot();
        float dYaw = Mth.wrapDegrees(targetYaw - curYaw);
        float dPitch = targetPitch - curPitch;

        float newYaw;
        float newPitch;
        String mode = choice("smooth-mode");
        if ("Linear".equals(mode) || "Sigmoid".equals(mode)) {

            float mag = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
            float turn = (float) decimal("turnSpeed");
            float hFactor = turn * (integer("horizontal-speed") / 100.0f);
            float vFactor = turn * (integer("vertical-speed") / 100.0f);
            if ("Sigmoid".equals(mode)) {
                float scaled = mag / 120.0f;
                float steep = (float) decimal("sigSteepness");
                float mid = (float) decimal("sigMidpoint");
                float sig = 1.0f / (1.0f + (float) Math.exp(-steep * (scaled - mid)));
                hFactor *= sig;
                vFactor *= sig;
            }
            float stepYaw = hFactor * dt;
            float stepPitch = vFactor * dt;
            if (mag < 1.0e-4f) {
                newYaw = targetYaw;
                newPitch = targetPitch;
            } else {
                float straightYaw = Math.abs(dYaw / mag) * stepYaw;
                float straightPitch = Math.abs(dPitch / mag) * stepPitch;
                newYaw = curYaw + Mth.clamp(dYaw, -straightYaw, straightYaw);
                newPitch = curPitch + Mth.clamp(dPitch, -straightPitch, straightPitch);
            }
        } else {

            float base = baseDecay();
            float decayYaw = base * (integer("horizontal-speed") / 100.0f);
            float decayPitch = base * (integer("vertical-speed") / 100.0f);
            float tYaw = 1.0f - (float) Math.exp(-decayYaw * dt);
            float tPitch = 1.0f - (float) Math.exp(-decayPitch * dt);
            newYaw = curYaw + dYaw * tYaw;
            newPitch = curPitch + dPitch * tPitch;
        }

        if (Math.abs(Mth.wrapDegrees(targetYaw - newYaw)) < 0.05f) newYaw = targetYaw;
        if (Math.abs(targetPitch - newPitch) < 0.05f) newPitch = targetPitch;

        applyRotation(p, newYaw, newPitch);
    }

    private float baseDecay() {
        float s = Mth.clamp(integer("smoothness") / 100.0f, 0.0f, 1.0f);
        return (float) (DECAY_MAX * Math.pow(DECAY_MIN / DECAY_MAX, s));
    }

    private void applyRotation(LocalPlayer p, float yaw, float pitch) {
        String axis = choice("axis");
        float finalYaw = "Vertical".equals(axis) ? p.getYRot() : Mth.wrapDegrees(yaw);
        float finalPitch = "Horizontal".equals(axis) ? p.getXRot() : Mth.clamp(pitch, -90.0f, 90.0f);
        AutismRotationUtil.apply(p, new AutismRotationUtil.Rotation(finalYaw, finalPitch), false);
    }

    private LivingEntity selectTarget(Minecraft mc, LocalPlayer me) {
        double rangeSq = decimal("range") * decimal("range");
        int fov = integer("fov");
        AutismRotationUtil.Rotation current = AutismRotationUtil.playerRotation(me);
        Vec3 eyes = me.getEyePosition();
        String priority = choice("priority");
        long now = System.currentTimeMillis();

        List<LivingEntity> nearby = mc.level.getEntitiesOfClass(LivingEntity.class,
            me.getBoundingBox().inflate(decimal("range")), e -> e != me);

        if (bool("sticky") && lockedId != null) {
            LivingEntity held = livingById(nearby, lockedId);
            if (held != null && (held.isRemoved() || !held.isAlive())) {
                lockedId = null;
                lockGraceUntil = 0L;
            } else {
                boolean good = held != null
                    && held.distanceToSqr(me) <= rangeSq
                    && aimPoint(mc, me, eyes, held) != null;

                if (good && held instanceof Player hp && (PvpUtil.isFriend(hp, BossPvpAddon.friends()) || (bool("teamCheck") && PvpUtil.isTeammate(me, hp)))) good = false;

                if (good && bool("showCircle") && bool("releaseOnLeave")
                        && !withinCircle(mc, me, held, eyes)) {
                    good = false;
                }
                if (good) {
                    lockGraceUntil = 0L;
                    return held;
                }
                if (lockGraceUntil == 0L) lockGraceUntil = now + STICKY_GRACE_MS;
                if (now < lockGraceUntil) {
                    return held;
                }
                lockedId = null;
                lockGraceUntil = 0L;
            }
        }

        LivingEntity best = null;
        for (LivingEntity living : nearby) {
            if (!valid(mc, me, living, rangeSq, fov, current, eyes)) continue;
            if (best == null || compareTargets(me, living, best, priority, current, eyes) < 0) best = living;
        }
        if (best == null) return null;

        if (lockedId != null && !lockedId.equals(best.getUUID()) && now - lastSwitchMs < integer("switchDelay")) {
            LivingEntity held = livingById(nearby, lockedId);
            if (held != null && held.isAlive() && !held.isRemoved()
                    && held.distanceToSqr(me) <= rangeSq
                    && aimPoint(mc, me, eyes, held) != null) {
                return held;
            }
        }
        if (!best.getUUID().equals(lockedId)) {
            lockedId = best.getUUID();
            lastSwitchMs = now;
            lockGraceUntil = 0L;
        }
        return best;
    }

    private boolean valid(Minecraft mc, LocalPlayer me, LivingEntity entity, double rangeSq, int fov,
                          AutismRotationUtil.Rotation current, Vec3 eyes) {
        if (entity == me || entity.isRemoved() || !entity.isAlive()) return false;
        if (entity.hurtTime > integer("hurt-time")) return false;
        if (!matchesEntity(entity)) return false;
        if (entity instanceof Player pl && (PvpUtil.isFriend(pl, BossPvpAddon.friends()) || (bool("teamCheck") && PvpUtil.isTeammate(me, pl)))) return false;
        if (entity.distanceToSqr(me) > rangeSq) return false;
        if (crosshairAngle(entity, current, eyes) > fov) return false;

        if (bool("showCircle") && !withinCircle(mc, me, entity, eyes)) return false;
        if (bool("requireLineOfSight") && aimPoint(mc, me, eyes, entity) == null) return false;
        return true;
    }

    private boolean withinCircle(Minecraft mc, LocalPlayer me, LivingEntity entity, Vec3 eyes) {
        Vec3 point = aimPoint(mc, me, eyes, entity);
        if (point == null) return false;
        return look3dAngleDeg(me, eyes, point) <= Math.toDegrees(circleHalfAngleRadians());
    }

    private double look3dAngleDeg(LocalPlayer me, Vec3 eyes, Vec3 point) {
        Vec3 look = me.getViewVector(1.0f);
        Vec3 dir = point.subtract(eyes);
        double len = dir.length();
        if (len < 1.0e-6) return 0.0;
        double dot = (look.x * dir.x + look.y * dir.y + look.z * dir.z) / len;
        dot = Math.max(-1.0, Math.min(1.0, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    private double circleHalfAngleRadians() {
        Minecraft mc = Minecraft.getInstance();
        double vh = AutismUiScale.getVirtualScreenHeight();
        if (vh <= 0) return Math.toRadians(Math.min(180, integer("fov")));
        double radius = integer("circleRadius");
        double fovYrad = Math.toRadians(renderFovDeg(mc));
        return Math.atan((2.0 * radius / vh) * Math.tan(fovYrad / 2.0));
    }

    private double renderFovDeg(Minecraft mc) {
        if (mc != null && mc.gameRenderer != null) {
            Camera cam = mc.gameRenderer.mainCamera();
            if (cam != null) {
                float f = cam.getFov();
                if (f > 0.0f) return f;
            }
        }
        return (mc != null && mc.options != null) ? mc.options.fov().get() : 70.0;
    }

    private int compareTargets(LocalPlayer me, LivingEntity a, LivingEntity b, String priority,
                               AutismRotationUtil.Rotation current, Vec3 eyes) {
        return switch (priority) {
            case "Type" -> Integer.compare(typeWeight(a), typeWeight(b));
            case "Health" -> Float.compare(a.getHealth() + a.getAbsorptionAmount(), b.getHealth() + b.getAbsorptionAmount());
            case "Distance" -> Double.compare(a.distanceToSqr(me), b.distanceToSqr(me));
            case "HurtTime" -> Integer.compare(a.hurtTime, b.hurtTime);
            case "Age" -> Integer.compare(b.tickCount, a.tickCount);
            default -> Float.compare(crosshairAngle(a, current, eyes), crosshairAngle(b, current, eyes));
        };
    }

    private int typeWeight(LivingEntity entity) {
        if (entity instanceof Player) return 0;
        if (entity instanceof Enemy) return 1;
        return 100;
    }

    private float crosshairAngle(LivingEntity entity, AutismRotationUtil.Rotation current, Vec3 eyes) {
        AutismRotationUtil.Rotation toEntity = AutismRotationUtil.lookingAt(entity.getBoundingBox().getCenter(), eyes);
        return AutismRotationUtil.angleTo(current, toEntity);
    }

    private boolean matchesEntity(Entity entity) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().toLowerCase(Locale.ROOT);
        String shortId = id.substring(id.indexOf(':') + 1);
        for (String entry : list("entities")) {
            if (entry == null) continue;
            String value = entry.trim().toLowerCase(Locale.ROOT);
            if (value.isEmpty()) continue;
            if (value.equals(id) || value.equals(shortId)) return true;
        }
        return false;
    }

    private Vec3 aimPoint(Minecraft mc, LocalPlayer me, Vec3 eyes, LivingEntity entity) {
        long tick = mc.level.getGameTime();
        if (tick != aimCacheTick) { aimPointCache.clear(); aimCacheTick = tick; }
        if (aimPointCache.containsKey(entity)) return aimPointCache.get(entity);
        Vec3 point = computeAimPoint(mc, me, eyes, entity);
        aimPointCache.put(entity, point);
        return point;
    }

    private Vec3 computeAimPoint(Minecraft mc, LocalPlayer me, Vec3 eyes, LivingEntity entity) {
        AABB box = entity.getBoundingBox().inflate(entity.getPickRadius());
        double cx = (box.minX + box.maxX) * 0.5;
        double cz = (box.minZ + box.maxZ) * 0.5;
        Vec3 preferred = switch (choice("target-point")) {
            case "Center", "Body" -> box.getCenter();
            case "Head" -> entity.getEyePosition();
            case "Feet" -> new Vec3(cx, box.minY + 0.1, cz);
            default -> nearestPoint(eyes, box);
        };
        if (!bool("requireLineOfSight")) return preferred;
        if (visible(mc, me, eyes, preferred)) return preferred;
        return bestVisiblePoint(mc, me, eyes, box);
    }

    private Vec3 nearestPoint(Vec3 eyes, AABB box) {
        Vec3 closest = new Vec3(
            Mth.clamp(eyes.x, box.minX, box.maxX),
            Mth.clamp(eyes.y, box.minY, box.maxY),
            Mth.clamp(eyes.z, box.minZ, box.maxZ)
        );
        if (closest.distanceToSqr(eyes) < 1.0E-6) return box.getCenter();
        return closest;
    }

    private Vec3 bestVisiblePoint(Minecraft mc, LocalPlayer me, Vec3 eyes, AABB box) {
        AutismRotationUtil.Rotation current = AutismRotationUtil.playerRotation(me);
        Vec3 best = null;
        float bestAngle = Float.MAX_VALUE;
        for (int ix = 0; ix <= 2; ix++) {
            double x = Mth.lerp(ix / 2.0, box.minX, box.maxX);
            for (int iy = 0; iy <= 2; iy++) {
                double y = Mth.lerp(iy / 2.0, box.minY, box.maxY);
                for (int iz = 0; iz <= 2; iz++) {
                    double z = Mth.lerp(iz / 2.0, box.minZ, box.maxZ);
                    Vec3 point = new Vec3(x, y, z);
                    if (!visible(mc, me, eyes, point)) continue;
                    float angle = AutismRotationUtil.angleTo(current, AutismRotationUtil.lookingAt(point, eyes));
                    if (angle < bestAngle) {
                        bestAngle = angle;
                        best = point;
                    }
                }
            }
        }
        return best;
    }

    private boolean visible(Minecraft mc, LocalPlayer me, Vec3 eyes, Vec3 point) {
        if (mc.level == null || point == null) return false;
        HitResult hit = mc.level.clip(new ClipContext(eyes, point, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, me));
        return hit == null || hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(point) < 0.05;
    }

    private LivingEntity livingById(List<LivingEntity> nearby, UUID id) {
        for (LivingEntity living : nearby) {
            if (living.getUUID().equals(id)) return living;
        }
        return null;
    }

    public boolean isLockedOn()   { return lockedOn; }
    public boolean showCircle()   { return bool("showCircle"); }
    public int circleRadius()     { return integer("circleRadius"); }
    public boolean circleFilled() { return bool("filled"); }
}
