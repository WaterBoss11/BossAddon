package com.boss.pvp.module.combat;

import net.minecraft.world.phys.Vec3;

final class RangedCombatDecisions {
    private RangedCombatDecisions() {}

    static boolean risingEdge(boolean previous, boolean current) {
        return current && !previous;
    }

    static boolean healthy(double health, double minimum) {
        return Double.isFinite(health) && Double.isFinite(minimum) && minimum >= 5.0 && health > minimum;
    }

    static boolean incoming(Vec3 towardPlayer, Vec3 velocity, double horizon, double radius) {
        if (towardPlayer == null || velocity == null || !Double.isFinite(horizon) || !Double.isFinite(radius)
                || horizon <= 0.0 || radius <= 0.0) return false;
        double speedSquared = velocity.lengthSqr();
        double approach = towardPlayer.dot(velocity);
        if (!Double.isFinite(speedSquared) || !Double.isFinite(approach) || speedSquared <= 1.0e-8 || approach <= 0.0) return false;
        double time = Math.min(horizon, approach / speedSquared);
        double missSquared = towardPlayer.subtract(velocity.scale(time)).lengthSqr();
        return Double.isFinite(missSquared) && missSquared <= radius * radius;
    }

    static boolean chargeReady(int usingTicks, int minimumTicks) {
        return minimumTicks > 0 && usingTicks >= minimumTicks;
    }
}
