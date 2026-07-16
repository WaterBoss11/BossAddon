package com.boss.pvp.util.pvp;

import autismclient.util.AutismRotationUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.DyedItemColor;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

public final class PvpUtil {

    public static final double VANILLA_REACH = 3.0;
    public static final double MAX_REACH = 3.5;

    private static final Random RNG = new Random();

    private PvpUtil() {}

    public static long cpsDelayMs(int minCps, int maxCps) {
        double lo = Math.max(1, Math.min(minCps, maxCps));
        double hi = Math.max(lo, Math.max(minCps, maxCps));
        double cps = lo + RNG.nextDouble() * (hi - lo);
        return (long) (1000.0 / Math.max(1.0, cps));
    }

    public static long jitterMs(long base) {
        return jitterMs(base, 0.25);
    }

    public static long jitterMs(long base, double frac) {
        double j = base * frac;
        return (long) (base - j + RNG.nextDouble() * (2 * j));
    }

    public static boolean roll(int percent) {
        return RNG.nextInt(100) < Math.max(0, Math.min(100, percent));
    }

    public static boolean fullCharge(LocalPlayer p) {
        return p.getAttackStrengthScale(0.0f) >= 1.0f;
    }

    public static boolean canSee(Minecraft mc, LocalPlayer me, Vec3 from, Vec3 to) {
        if (mc.level == null || from == null || to == null) return false;
        HitResult hit = mc.level.clip(new ClipContext(from, to,
            ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, me));
        return hit == null || hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(to) < 0.05;
    }

    public static boolean canSeeEntity(Minecraft mc, LocalPlayer me, Entity target) {
        Vec3 eyes = me.getEyePosition();
        return canSee(mc, me, eyes, target.getBoundingBox().getCenter())
            || canSee(mc, me, eyes, target.getEyePosition());
    }

    public static Set<String> entityIds(List<String> entries) {
        Set<String> ids = new LinkedHashSet<>();
        if (entries == null) return ids;
        for (String entry : entries) {
            if (entry == null) continue;
            String v = entry.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) ids.add(v);
        }
        return ids;
    }

    public static boolean matchesEntity(Entity entity, Set<String> ids) {
        if (ids.isEmpty()) return false;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().toLowerCase(Locale.ROOT);
        if (ids.contains(id)) return true;
        int c = id.indexOf(':');
        return c >= 0 && ids.contains(id.substring(c + 1));
    }

    private static long livingCacheTick = Long.MIN_VALUE;
    private static Level livingCacheLevel = null;
    private static final List<LivingEntity> livingCache = new ArrayList<>();

    private static List<LivingEntity> livingEntities(Minecraft mc) {
        long tick = mc.level.getGameTime();
        if (tick != livingCacheTick || mc.level != livingCacheLevel) {
            livingCache.clear();
            for (Entity entity : mc.level.entitiesForRendering()) {
                if (entity instanceof LivingEntity living) livingCache.add(living);
            }
            livingCacheTick = tick;
            livingCacheLevel = mc.level;
        }
        return livingCache;
    }

    public static LivingEntity findTarget(Minecraft mc, LocalPlayer me, double range, double fov,
                                          Set<String> ids, boolean requireLos, String sort) {
        if (mc.level == null) return null;
        double rangeSq = range * range;
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        List<LivingEntity> living = livingEntities(mc);
        for (int i = 0; i < living.size(); i++) {
            LivingEntity entity = living.get(i);
            if (entity == me || entity.isRemoved() || !entity.isAlive()) continue;
            if (!matchesEntity(entity, ids)) continue;
            if (entity.distanceToSqr(me) > rangeSq) continue;
            if (fov < 180.0 && angleTo(me, entity) > fov) continue;
            if (requireLos && !canSeeEntity(mc, me, entity)) continue;
            double score = sortScore(me, entity, sort);
            if (score < bestScore) { bestScore = score; best = entity; }
        }
        return best;
    }

    private static double sortScore(LocalPlayer me, LivingEntity e, String sort) {
        return switch (sort == null ? "Distance" : sort) {
            case "Health" -> e.getHealth() + e.getAbsorptionAmount();
            case "Angle" -> angleTo(me, e);
            case "LowestHpThenDistance" -> (e.getHealth() + e.getAbsorptionAmount()) * 10000.0 + Math.sqrt(e.distanceToSqr(me));
            default -> e.distanceToSqr(me);
        };
    }

    public static float angleTo(LocalPlayer me, Entity e) {
        Vec3 eyes = me.getEyePosition();
        Vec3 diff = e.getBoundingBox().getCenter().subtract(eyes);
        double yaw = Math.toDegrees(Math.atan2(diff.z, diff.x)) - 90.0;
        double horiz = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        double pitch = -Math.toDegrees(Math.atan2(diff.y, horiz));
        float dYaw = net.minecraft.util.Mth.degreesDifferenceAbs((float) yaw, me.getYRot());
        float dPitch = Math.abs((float) pitch - me.getXRot());
        return (float) Math.min(180.0, Math.sqrt(dYaw * dYaw + dPitch * dPitch));
    }

    public static double distSqToBox(Vec3 p, AABB box) {
        double dx = Math.max(Math.max(box.minX - p.x, 0), p.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - p.y, 0), p.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - p.z, 0), p.z - box.maxZ);
        return dx * dx + dy * dy + dz * dz;
    }

    public static BlockHitResult placeHitResult(Level level, BlockPos pos) {
        BlockState here = level.getBlockState(pos);
        if (!here.isAir() && !here.canBeReplaced()) return null;
        for (Direction d : Direction.values()) {
            BlockPos neighbor = pos.relative(d);
            BlockState ns = level.getBlockState(neighbor);
            if (ns.isAir() || !ns.getFluidState().isEmpty()) continue;
            Direction face = d.getOpposite();
            Vec3 hit = Vec3.atCenterOf(neighbor)
                .add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            return new BlockHitResult(hit, face, neighbor, false);
        }
        return null;
    }

    public static boolean isAirOrReplaceable(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return s.isAir() || s.canBeReplaced();
    }

    public static void ghostSafeUseOn(Minecraft mc, LocalPlayer p, BlockHitResult hit) {
        if (mc == null || p == null || hit == null || mc.gameMode == null) return;
        AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(p);
        AutismRotationUtil.Rotation look = AutismRotationUtil.normalizeToSensitivity(
            AutismRotationUtil.lookingAt(hit.getLocation(), p.getEyePosition()), cur);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                look.yaw(), look.pitch(), p.onGround(), p.horizontalCollision));
        }
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
    }

    public static void ghostSafePlace(Minecraft mc, LocalPlayer p, BlockHitResult hit) {
        ghostSafeUseOn(mc, p, hit);
    }

    public static void ghostSafeAttack(Minecraft mc, LocalPlayer p, Entity target, Vec3 lookPoint, boolean swing) {
        if (mc == null || p == null || target == null || mc.gameMode == null) return;
        AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(p);
        AutismRotationUtil.Rotation look = AutismRotationUtil.normalizeToSensitivity(
            AutismRotationUtil.lookingAt(lookPoint, p.getEyePosition()), cur);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                look.yaw(), look.pitch(), p.onGround(), p.horizontalCollision));
        }
        mc.gameMode.attack(p, target);
        if (swing) p.swing(InteractionHand.MAIN_HAND);
    }

    private static final EquipmentSlot[] TEAM_ARMOR_SLOTS =
        { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
    private static final int TEAM_COLOR_TOLERANCE = 15;
    private static final int TEAM_MIN_MATCHES = 2;

    public static boolean isFriend(Player target, List<String> friends) {
        if (target == null || friends == null || friends.isEmpty()) return false;
        String name = target.getGameProfile().name();
        if (name == null || name.isBlank()) return false;
        for (String f : friends) {
            if (f != null && f.trim().equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    public static boolean isTeammate(LocalPlayer self, Player target) {
        if (self == null || target == null || self == target) return false;
        int matches = 0;
        boolean selfHasDyed = false;
        for (EquipmentSlot slot : TEAM_ARMOR_SLOTS) {
            Integer selfColor = dyedLeatherColor(self.getItemBySlot(slot));
            if (selfColor == null) continue;
            selfHasDyed = true;
            Integer targetColor = dyedLeatherColor(target.getItemBySlot(slot));
            if (targetColor == null) continue;
            if (colorsMatch(selfColor, targetColor)) matches++;
        }
        if (!selfHasDyed) return false;
        return matches >= TEAM_MIN_MATCHES;
    }

    private static Integer dyedLeatherColor(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        var item = stack.getItem();
        if (item != Items.LEATHER_HELMET && item != Items.LEATHER_CHESTPLATE
                && item != Items.LEATHER_LEGGINGS && item != Items.LEATHER_BOOTS) return null;
        DyedItemColor dyed = stack.get(DataComponents.DYED_COLOR);
        return dyed == null ? null : dyed.rgb();
    }

    private static boolean colorsMatch(int a, int b) {
        int dr = Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF));
        int dg = Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF));
        int db = Math.abs((a & 0xFF) - (b & 0xFF));
        return dr <= TEAM_COLOR_TOLERANCE && dg <= TEAM_COLOR_TOLERANCE && db <= TEAM_COLOR_TOLERANCE;
    }
}
