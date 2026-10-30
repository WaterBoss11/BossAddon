package com.boss.pvp.util.pvp;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Air-flight prediction for 26.2 snowballs, eggs, ender pearls, and fishing rods. */
public final class ProjectileMath {

    public enum Kind { THROWABLE, FISHING_ROD }
    public record Shot(float yaw, float pitch, Vec3 velocity, double ticks) {}
    public record Impact(HitResult hit, double ticks) {}

    public static final int MAX_TICKS = 80;
    private static final double MAX_LEAD_TICKS = 10.0;
    private static final double MAX_TARGET_SPEED = 1.5;
    private static final double THROW_DRAG = (double) 0.99f;
    private static final double ROD_DRAG = 0.92;
    private static final double GRAVITY = 0.03;

    private ProjectileMath() {}

    public static Vec3 origin(Kind kind, Vec3 eyes, float yaw) {
        double radians = Math.toRadians(yaw);
        return kind == Kind.FISHING_ROD
            ? eyes.add(-Math.sin(radians) * 0.3, 0.0, Math.cos(radians) * 0.3)
            : eyes.add(0.0, -0.1, 0.0);
    }

    public static Vec3 launchVelocity(Kind kind, float yaw, float pitch, Vec3 inheritedVelocity) {
        double y = Math.toRadians(yaw);
        double p = Math.toRadians(pitch);
        if (kind == Kind.FISHING_ROD) {
            Vec3 direction = new Vec3(-Math.sin(y), Math.clamp(-Math.tan(p), -5.0, 5.0), Math.cos(y));
            return direction.normalize().scale(0.6).add(direction.scale(0.5));
        }
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p))
            .scale(1.5).add(inheritedVelocity);
    }

    // ponytail: air-only, constant velocity lead capped at ten ticks; use a native simulator for fluids/acceleration.
    public static Vec3 leadOffset(Vec3 velocity, double ticks) {
        double length = velocity.length();
        Vec3 bounded = length > MAX_TARGET_SPEED ? velocity.scale(MAX_TARGET_SPEED / length) : velocity;
        return bounded.scale(Math.clamp(ticks, 0.0, MAX_LEAD_TICKS));
    }

    /** Current position/velocity are also valid inputs: advances the next native air-flight tick, not a fresh launch. */
    public static Vec3 positionAt(Kind kind, Vec3 position, Vec3 velocity, double ticks) {
        double remaining = Math.clamp(ticks, 0.0, MAX_TICKS);
        while (remaining > 0.0) {
            Vec3 movement = movement(kind, velocity);
            position = position.add(movement.scale(Math.min(1.0, remaining)));
            velocity = kind == Kind.FISHING_ROD ? movement.scale(ROD_DRAG) : movement;
            remaining -= 1.0;
        }
        return position;
    }

    /** Finds the earliest (low-arc) intercept; no solution is preferable to an unbounded or unreachable lead. */
    public static Shot solve(Kind kind, Vec3 origin, Vec3 inheritedVelocity, Vec3 targetPoint,
                             Vec3 targetVelocity, int maxTicks) {
        if (kind == null || !finite(origin) || !finite(inheritedVelocity) || !finite(targetPoint)
                || !finite(targetVelocity) || maxTicks < 1 || origin.distanceToSqr(targetPoint) < 1.0e-8) return null;
        int limit = Math.min(MAX_TICKS, maxTicks);
        Vec3 inherited = kind == Kind.FISHING_ROD ? Vec3.ZERO : inheritedVelocity;
        double previousTime = 0.0;
        double previousError = Double.POSITIVE_INFINITY;
        for (double time = 0.125; time <= limit; time += 0.125) {
            double error = speedError(kind, requiredVelocity(kind, origin, inherited, targetPoint, targetVelocity, time));
            if (error <= 0.0 && previousError > 0.0) {
                double low = previousTime;
                double high = time;
                for (int i = 0; i < 24; i++) {
                    double middle = (low + high) * 0.5;
                    double difference = speedError(kind,
                        requiredVelocity(kind, origin, inherited, targetPoint, targetVelocity, middle));
                    if (difference > 0.0) low = middle;
                    else high = middle;
                }
                double flight = (low + high) * 0.5;
                Vec3 required = requiredVelocity(kind, origin, inherited, targetPoint, targetVelocity, flight);
                float yaw = (float) (Math.toDegrees(Math.atan2(required.z, required.x)) - 90.0);
                float pitch = (float) -Math.toDegrees(Math.atan2(required.y, Math.hypot(required.x, required.z)));
                Vec3 launch = launchVelocity(kind, yaw, pitch, inherited);
                Vec3 intercept = targetPoint.add(leadOffset(targetVelocity, flight));
                if (positionAt(kind, origin, launch, flight).distanceToSqr(intercept) < 0.0025) {
                    return new Shot(yaw, pitch, launch, flight);
                }
            }
            previousTime = time;
            previousError = error;
        }
        return null;
    }

    private static Vec3 requiredVelocity(Kind kind, Vec3 origin, Vec3 inherited, Vec3 target,
                                         Vec3 targetVelocity, double ticks) {
        if (ticks <= 0.0) return new Vec3(Double.POSITIVE_INFINITY, 0.0, 0.0);
        Vec3 gravity = positionAt(kind, Vec3.ZERO, Vec3.ZERO, ticks);
        double travel = positionAt(kind, Vec3.ZERO, new Vec3(1.0, 0.0, 0.0), ticks).x;
        return target.add(leadOffset(targetVelocity, ticks)).subtract(origin).subtract(gravity)
            .scale(1.0 / travel).subtract(inherited);
    }

    private static double speedError(Kind kind, Vec3 required) {
        if (!finite(required)) return Double.POSITIVE_INFINITY;
        if (kind == Kind.THROWABLE) return required.length() - 1.5;
        double horizontal = Math.hypot(required.x, required.z);
        if (horizontal < 1.0e-8 || Math.abs(required.y / horizontal) > 5.0) return Double.POSITIVE_INFINITY;
        return required.length() - (0.6 + 0.5 * required.length() / horizontal);
    }

    /**
     * First impact from a new shot OR an existing projectile's current position and velocity.
     * For an existing projectile, pass that projectile as the excluded/context entity, not the local player.
     * A bounded MISS is returned when the horizon expires; null means invalid/unloaded/fluid prediction.
     * The owner is excluded, and moving entities are tested before any farther block impact.
     * Entity padding uses the native maximum (0.3); projectile age and random launch spread are not simulated.
     */
    public static Impact trace(Level level, Entity owner, Kind kind, Vec3 position, Vec3 velocity, int maxTicks) {
        return trace(level, owner, null, kind, position, velocity, maxTicks);
    }

    /** Require the actual target box, but conservatively pad blockers by the maximum native projectile margin. */
    public static boolean clearShot(Level level, Entity owner, Entity target, Kind kind, Vec3 position, Vec3 velocity, int maxTicks) {
        if (target == null) return false;
        Impact impact = trace(level, owner, target, kind, position, velocity, maxTicks);
        return impact != null && impact.hit() instanceof EntityHitResult hit && hit.getEntity() == target;
    }

    private static Impact trace(Level level, Entity owner, Entity target, Kind kind, Vec3 position, Vec3 velocity, int maxTicks) {
        if (level == null || owner == null || kind == null || !finite(position) || !finite(velocity) || maxTicks < 1
                || velocity.lengthSqr() > 256.0 || Math.abs(position.x) > 30_000_000.0 || Math.abs(position.z) > 30_000_000.0) return null;
        int limit = Math.min(MAX_TICKS, maxTicks);
        for (int tick = 0; tick < limit; tick++) {
            Vec3 movement = movement(kind, velocity);
            Vec3 next = position.add(movement);
            int samples = Math.max(1, (int) Math.ceil(movement.length() * 2.0));
            for (int i = 0; i <= samples; i++) {
                BlockPos cell = BlockPos.containing(position.lerp(next, (double) i / samples));
                if (!level.isLoaded(cell) || !level.getFluidState(cell).isEmpty()) return null;
            }
            BlockHitResult block = level.clip(new ClipContext(position, next,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, owner));
            if (block.getType() == HitResult.Type.BLOCK && !level.getFluidState(block.getBlockPos()).isEmpty()) return null;
            HitResult closest = block.getType() == HitResult.Type.BLOCK ? block : null;
            double distance = closest == null ? Double.POSITIVE_INFINITY : position.distanceToSqr(closest.getLocation());
            AABB search = new AABB(position, next).inflate(MAX_LEAD_TICKS * MAX_TARGET_SPEED + 0.5);
            for (Entity entity : level.getEntities(owner, search,
                    e -> e.isAlive() && !e.isRemoved() && !e.isSpectator() && e.canBeHitByProjectile()
                        && !e.isPassengerOfSameVehicle(owner))) {
                Vec3 entityVelocity = entity.getKnownMovement();
                if (!finite(entityVelocity)) return null;
                AABB box = entity == target ? entity.getBoundingBox() : entity.getBoundingBox().inflate((double) 0.3f);
                Vec3 hit = movingBoxHit(box, entityVelocity, tick, position, next);
                if (hit != null && position.distanceToSqr(hit) < distance) {
                    closest = new EntityHitResult(entity, hit);
                    distance = position.distanceToSqr(hit);
                }
            }
            if (closest != null) {
                double fraction = movement.lengthSqr() < 1.0e-12 ? 0.0 : Math.sqrt(distance / movement.lengthSqr());
                return new Impact(closest, tick + fraction);
            }
            position = next;
            velocity = kind == Kind.FISHING_ROD ? movement.scale(ROD_DRAG) : movement;
        }
        return new Impact(BlockHitResult.miss(position, Direction.UP, BlockPos.containing(position)), limit);
    }

    static Vec3 movingBoxHit(AABB box, Vec3 targetVelocity, double tick, Vec3 from, Vec3 to) {
        Vec3 offset = leadOffset(targetVelocity, tick);
        Vec3 delta = leadOffset(targetVelocity, tick + 1.0).subtract(offset);
        AABB initial = box.move(offset);
        if (initial.contains(from)) return from;
        Vec3 relativeEnd = to.subtract(delta);
        Vec3 relativeHit = initial.clip(from, relativeEnd).orElse(null);
        if (relativeHit == null) return null;
        double distance = relativeEnd.distanceTo(from);
        double fraction = distance < 1.0e-8 ? 0.0 : from.distanceTo(relativeHit) / distance;
        return from.lerp(to, Math.clamp(fraction, 0.0, 1.0));
    }

    private static Vec3 movement(Kind kind, Vec3 velocity) {
        Vec3 falling = velocity.add(0.0, -GRAVITY, 0.0);
        return kind == Kind.FISHING_ROD ? falling : falling.scale(THROW_DRAG);
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
