package com.boss.pvp.util.pvp;

/**
 * Pure knockback-math helpers for {@link com.boss.pvp.module.combat.VelocityModule}, extracted so the scaling
 * and strafe-direction math is unit-testable without a client. No Minecraft types.
 */
public final class VelocityMath {

    private VelocityMath() {}

    /** Scale a motion component by a percentage: {@code keep 40} → {@code value * 0.40}. 0 zeroes it, 100 keeps it. */
    public static double scale(double value, int percent) {
        return value * (percent / 100.0);
    }

    /** Reversed component: flip the sign and scale by {@code strengthPercent}. */
    public static double reverse(double value, int strengthPercent) {
        return -value * (strengthPercent / 100.0);
    }

    /** Horizontal speed of an x/z delta. */
    public static double horizontalSpeed(double dx, double dz) {
        return Math.hypot(dx, dz);
    }

    /** Unit look-direction X for a yaw in degrees (vanilla convention: 0° faces -Z). */
    public static double dirX(float yawDeg) {
        return -Math.sin(Math.toRadians(yawDeg));
    }

    /** Unit look-direction Z for a yaw in degrees. */
    public static double dirZ(float yawDeg) {
        return Math.cos(Math.toRadians(yawDeg));
    }
}
