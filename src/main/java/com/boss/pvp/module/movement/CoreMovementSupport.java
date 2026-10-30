package com.boss.pvp.module.movement;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;

public final class CoreMovementSupport {
    static final double EPSILON = 1.0e-5;

    record Horizontal(double x, double z) {}
    record Landing(Vec3 feet, int ticks) {}

    private CoreMovementSupport() {}

    static boolean ready(Minecraft mc) {
        return mc != null && mc.player != null && mc.level != null && mc.gameMode != null
            && mc.getConnection() != null && mc.options != null && mc.gui.screen() == null && !mc.isPaused() && mc.isWindowActive()
            && mc.player.isAlive() && !mc.player.isRemoved() && !mc.player.isSpectator()
            && !mc.player.isPassenger() && !mc.player.isSleeping();
    }

    static boolean groundState(LocalPlayer player) {
        return !player.isFallFlying() && !player.getAbilities().flying && !player.isInLiquid()
            && !player.isSwimming() && !player.onClimbable() && !player.isInPowderSnow;
    }

    static boolean horizontalInput(Minecraft mc) {
        return mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
            || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
    }

    static boolean loaded(Level level, AABB box) {
        if (!finite(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)
            || box.minY < level.getMinY() || box.maxY >= level.getMaxY()
            || !level.getWorldBorder().isWithinBounds(box)) return false;
        int minX = (int) Math.floor(box.minX) >> 4;
        int maxX = (int) Math.floor(box.maxX) >> 4;
        int minZ = (int) Math.floor(box.minZ) >> 4;
        int maxZ = (int) Math.floor(box.maxZ) >> 4;
        if (maxX - minX > 2 || maxZ - minZ > 2) return false;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!level.hasChunkAt(new BlockPos(x << 4, (int) box.minY, z << 4))) return false;
            }
        }
        return true;
    }

    static boolean hazardous(BlockState state) {
        return !state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
            || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(Blocks.COBWEB)
            || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
            || state.is(Blocks.SLIME_BLOCK) || state.is(Blocks.HONEY_BLOCK) || state.is(Blocks.POINTED_DRIPSTONE);
    }

    static boolean hazards(Level level, AABB box) {
        if (!loaded(level, box)) return true;
        int minX = (int) Math.floor(box.minX), maxX = (int) Math.floor(box.maxX - EPSILON);
        int minY = (int) Math.floor(box.minY), maxY = (int) Math.floor(box.maxY - EPSILON);
        int minZ = (int) Math.floor(box.minZ), maxZ = (int) Math.floor(box.maxZ - EPSILON);
        // ponytail: at most 1024 cells per query; a chunk-indexed hazard cache is the upgrade for longer paths.
        if ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 1024) return true;
        for (BlockPos pos : BlockPos.betweenClosed(minX, minY, minZ, maxX, maxY, maxZ)) {
            if (hazardous(level.getBlockState(pos))) return true;
        }
        return false;
    }

    static boolean occupied(Minecraft mc, LocalPlayer player, AABB box) {
        return !mc.level.getEntities(player, box, entity -> entity.isAlive() && !entity.isSpectator()
            && (entity instanceof LivingEntity || entity.isPickable())).isEmpty();
    }

    public static boolean clearPath(Minecraft mc, LocalPlayer player, AABB box, Vec3 movement) {
        AABB sweep = box.expandTowards(movement).deflate(EPSILON);
        return loaded(mc.level, sweep) && !hazards(mc.level, sweep)
            && mc.level.noCollision(player, sweep) && !occupied(mc, player, sweep);
    }

    static double groundDrop(Minecraft mc, LocalPlayer player, AABB box, double maximum) {
        if (!Double.isFinite(maximum) || maximum < 0 || maximum > 5) return Double.NaN;
        double probe = maximum + 0.05;
        AABB sweep = box.expandTowards(0, -probe, 0);
        if (!loaded(mc.level, sweep)) return Double.NaN;
        double clipped = Shapes.collide(Direction.Axis.Y, box,
            mc.level.getBlockCollisions(player, sweep), -probe);
        double drop = -clipped;
        if (clipped <= -probe + EPSILON || drop < -EPSILON || drop > maximum + EPSILON) return Double.NaN;
        AABB landed = box.move(0, clipped, 0);
        if (!mc.level.noCollision(player, landed.deflate(EPSILON)) || occupied(mc, player, landed)
            || hazards(mc.level, landed.expandTowards(0, -0.05, 0).deflate(EPSILON))) return Double.NaN;
        for (double x : new double[] {landed.minX + 0.05, landed.maxX - 0.05}) {
            for (double z : new double[] {landed.minZ + 0.05, landed.maxZ - 0.05}) {
                AABB corner = new AABB(x - 0.01, landed.minY + EPSILON, z - 0.01,
                    x + 0.01, landed.minY + 0.1, z + 0.01);
                double support = Shapes.collide(Direction.Axis.Y, corner,
                    mc.level.getBlockCollisions(player, corner.expandTowards(0, -0.1, 0)), -0.1);
                if (support <= -0.1 + EPSILON) return Double.NaN;
            }
        }
        return Math.max(0, drop);
    }

    static Landing landing(Minecraft mc, LocalPlayer player, int ticks) {
        AABB box = player.getBoundingBox();
        Vec3 velocity = player.getDeltaMovement();
        double gravity = player.getGravity();
        if (!Double.isFinite(gravity) || gravity <= 0 || gravity > 0.2) return null;
        // ponytail: seven ballistic ticks with native collision clipping, not a full input/effect simulation.
        for (int tick = 1; tick <= Math.min(7, ticks); tick++) {
            AABB sweep = box.expandTowards(velocity);
            if (!loaded(mc.level, sweep) || hazards(mc.level, sweep.deflate(EPSILON))) return null;
            Vec3 clipped = Entity.collideBoundingBox(player, velocity, box, mc.level,
                mc.level.getEntityCollisions(player, sweep));
            if (Math.abs(clipped.x - velocity.x) > EPSILON || Math.abs(clipped.z - velocity.z) > EPSILON) return null;
            box = box.move(clipped);
            if (velocity.y < 0 && clipped.y > velocity.y + EPSILON) {
                if (!Double.isFinite(groundDrop(mc, player, box, 0.05))) return null;
                return new Landing(new Vec3(box.getCenter().x, box.minY, box.getCenter().z), tick);
            }
            if (Math.abs(clipped.y - velocity.y) > EPSILON) return null;
            velocity = velocity.multiply(0.91, 0.98, 0.91).add(0, -gravity * 0.98, 0);
        }
        return null;
    }

    static Horizontal toward(double x, double z, double speed) {
        double length = Math.hypot(x, z);
        if (!finite(x, z, speed) || speed <= 0 || length <= EPSILON) return new Horizontal(0, 0);
        double scale = Math.min(1, speed / length);
        return new Horizontal(x * scale, z * scale);
    }

    static Horizontal orbit(double x, double z, double radius, double speed, int direction) {
        double distance = Math.hypot(x, z);
        if (!finite(x, z, radius, speed) || radius <= 0 || speed <= 0 || distance <= EPSILON) {
            return new Horizontal(0, 0);
        }
        double radial = Math.clamp((distance - radius) * 0.6, -0.75, 0.75);
        int side = direction < 0 ? -1 : 1;
        return toward(x / distance * radial - z / distance * side,
            z / distance * radial + x / distance * side, speed);
    }

    static boolean arrived(double x, double y, double z) {
        return finite(x, y, z) && x * x + z * z <= 0.06 * 0.06 && Math.abs(y) <= 0.15;
    }

    static double dropBudget(double maximum, double groundY, double currentY) {
        return finite(maximum, groundY, currentY) && maximum >= 0
            ? maximum - Math.max(0, groundY - currentY) : Double.NaN;
    }

    static double descent(double currentY, double drop, double speed) {
        if (!finite(currentY, drop, speed) || currentY >= 0 || drop <= EPSILON || speed <= 0) return currentY;
        return Math.max(-drop, Math.min(currentY, -speed));
    }

    static float turn(float current, float desired, float maximum) {
        if (!finite(current, desired, maximum) || maximum <= 0) return current;
        double delta = (double) desired - current;
        delta -= Math.floor((delta + 180) / 360) * 360;
        return current + (float) Math.clamp(delta, -maximum, maximum);
    }

    static boolean needsSeparation(double distance, double separation, double closingSpeed, double ticks) {
        return !finite(distance, separation, closingSpeed, ticks)
            || distance <= separation + Math.max(0, closingSpeed) * Math.max(0, ticks);
    }

    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
}
