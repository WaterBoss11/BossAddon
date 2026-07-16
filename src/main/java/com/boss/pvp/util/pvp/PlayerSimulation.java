package com.boss.pvp.util.pvp;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlayerSimulation {

    private static final double GRAVITY = 0.08;
    private static final double VERTICAL_DRAG = 0.98;
    private static final double SLIPPERINESS = 0.6;
    private static final double GROUND_FRICTION = 0.91 * SLIPPERINESS;
    private static final double AIR_FRICTION = 0.91;

    private PlayerSimulation() {}

    public static List<Vec3> simulate(Vec3 startPos, Vec3 startVel, boolean onGround, int ticks) {
        List<Vec3> out = new ArrayList<>(Math.max(0, ticks));
        double px = startPos.x, py = startPos.y, pz = startPos.z;
        double vx = startVel.x, vy = startVel.y, vz = startVel.z;
        for (int i = 0; i < ticks; i++) {
            vy = onGround ? 0.0 : (vy - GRAVITY) * VERTICAL_DRAG;
            px += vx;
            py += vy;
            pz += vz;
            double f = onGround ? GROUND_FRICTION : AIR_FRICTION;
            vx *= f;
            vz *= f;
            out.add(new Vec3(px, py, pz));
        }
        return out;
    }

    public static Vec3 predictPosition(Player target, int ticks) {
        if (target == null) return null;
        if (ticks <= 0) return target.position();
        List<Vec3> traj = simulateTrajectory(target, ticks);
        return traj.isEmpty() ? target.position() : traj.get(traj.size() - 1);
    }

    public static List<Vec3> simulateTrajectory(Player target, int ticks) {
        if (target == null || ticks <= 0) return List.of();
        Level level = target.level();
        long now = level.getGameTime();
        if (now != lastCacheGameTime || level != lastCacheLevel) {
            cache.clear();
            lastCacheGameTime = now;
            lastCacheLevel = level;
        }
        UUID id = target.getUUID();
        Cached c = cache.get(id);
        if (c != null && c.ticks() >= ticks) {
            return c.traj().subList(0, ticks);
        }
        List<Vec3> traj = simulate(target.position(), target.getDeltaMovement(), target.onGround(), ticks);
        cache.put(id, new Cached(ticks, traj));
        return traj;
    }

    public static void reset() {
        cache.clear();
        lastCacheGameTime = -1L;
        lastCacheLevel = null;
    }

    private record Cached(int ticks, List<Vec3> traj) {}

    private static final Map<UUID, Cached> cache = new HashMap<>();
    private static long lastCacheGameTime = -1L;
    private static Level lastCacheLevel = null;
}
