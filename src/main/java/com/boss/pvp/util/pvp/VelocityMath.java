package com.boss.pvp.util.pvp;

/**
 * Pure knockback-math helpers for {@link com.boss.pvp.module.combat.VelocityModule}, extracted so the scaling
 * and strafe-direction math is unit-testable without a client. No Minecraft types.
 */
public final class VelocityMath {

    private VelocityMath() {}

    /**
     * Maximum absolute value allowed for a single incoming motion axis (blocks/tick), used by the velocity
     * crash guard. Chosen far above any legitimate speed — vanilla movement, elytra + firework boosts, riptide
     * tridents and even extreme TNT/explosion launches all stay well under ~100 blocks/tick — and far below the
     * values that overflow Minecraft's own position/section math. Real crash reports carried ~1.8e38 / 2.8e38 /
     * 2.1e38 per axis; this cap sits ~100x above anything legitimate and ~34 orders of magnitude below those.
     */
    public static final double MAX_MOTION_PER_AXIS = 1.0e4;

    /**
     * Clamp one incoming motion component to a sane range so a malformed/malicious value can never reach
     * vanilla's collision/section math and overflow it. {@code NaN} → 0 (no direction to preserve); {@code
     * +/-Infinity} and any finite value beyond the cap → {@code +/-}{@link #MAX_MOTION_PER_AXIS}; every value
     * already in range is returned unchanged (bit-for-bit), so legitimate speeds are never touched.
     */
    public static double clampMotion(double v) {
        if (Double.isNaN(v)) return 0.0;
        if (v >  MAX_MOTION_PER_AXIS) return  MAX_MOTION_PER_AXIS;   // also catches +Infinity
        if (v < -MAX_MOTION_PER_AXIS) return -MAX_MOTION_PER_AXIS;   // also catches -Infinity
        return v;
    }

    /** True when clamping would change any axis — i.e. some component is non-finite or beyond the cap. */
    public static boolean needsClamp(double x, double y, double z) {
        return clampMotion(x) != x || clampMotion(y) != y || clampMotion(z) != z;
    }

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
