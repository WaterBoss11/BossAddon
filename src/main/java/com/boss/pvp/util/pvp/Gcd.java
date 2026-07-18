package com.boss.pvp.util.pvp;

import autismclient.util.AutismRotationUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.util.Mth;

/**
 * GCD — mouse-sensitivity rotation quantization, studied from LiquidBounce's
 * {@code RotationUtil.gcd} + {@code Rotation.normalize} and rewritten from scratch in Java.
 *
 * <p>Vanilla turns a raw integer mouse delta into a view rotation by scaling it with a fixed
 * function of the player's mouse sensitivity. Because the OS delivers mouse movement in whole
 * units, every rotation change a real mouse can produce is an <em>integer multiple</em> of one
 * smallest step — the "GCD" (greatest common divisor) of all achievable rotation deltas:
 *
 * <pre>  step = (sensitivity * 0.6 + 0.2)^3 * 8 * 0.15   (degrees per raw mouse unit)</pre>
 *
 * <p>Aimbot/aim-assist rotations that are <em>not</em> multiples of this step are trivially
 * flagged by rotation-analysis anti-cheats (NCP/Grim style) as machine-generated. {@link
 * #normalize} snaps the delta between the last rotation and a desired rotation onto that grid,
 * so silent/assisted aim lands where a physical mouse would — decoupling the true aim direction
 * from the value actually sent in the movement packet.
 *
 * <p>The {@code step}/{@code normalize}/{@code snapDelta}/{@code angleDifference} methods are pure
 * (sensitivity is a parameter) and unit-tested. {@link #liveStep} and {@link #normalize(
 * AutismRotationUtil.Rotation, AutismRotationUtil.Rotation)} read {@link Minecraft} for the live
 * sensitivity and are the client-facing entry points used by the combat/aim modules.
 */
public final class Gcd {

    // Vanilla sensitivity curve: f = sens*0.6 + 0.2, then f^3 * 8, then * 0.15 (the 1.13+ mouse
    // turn scale). Scoping (spyglass/bow zoom without smooth camera) drops the *8, shrinking the step.
    private static final double SENS_MUL = 0.6;
    private static final double SENS_ADD = 0.2;
    private static final double CUBE_SCALE = 8.0;
    private static final double MOUSE_TURN_SCALE = 0.15;

    private Gcd() {}

    /**
     * Degrees of rotation per single raw mouse unit for the given sensitivity (0..1).
     *
     * @param scoped true while zoomed (spyglass/bow) with smooth camera off — drops the {@code *8}.
     */
    public static double step(double sensitivity, boolean scoped) {
        double f = sensitivity * SENS_MUL + SENS_ADD;
        double cube = f * f * f;
        double factor = scoped ? cube : cube * CUBE_SCALE;
        return factor * MOUSE_TURN_SCALE;
    }

    /** Unscoped step for the given sensitivity. */
    public static double step(double sensitivity) {
        return step(sensitivity, false);
    }

    /** Shortest signed angle {@code a - b}, wrapped to (-180, 180]. */
    public static float angleDifference(float a, float b) {
        return Mth.wrapDegrees(a - b);
    }

    /** Round a rotation delta to the nearest whole multiple of {@code step}. step<=0 is a no-op. */
    public static float snapDelta(float delta, double step) {
        if (step <= 0.0) return delta;
        return (float) (Math.round(delta / step) * step);
    }

    /**
     * GCD-normalize a desired rotation relative to a current one: the returned yaw/pitch differ
     * from {@code current} only by whole multiples of {@code step}. Pitch is clamped to [-90, 90].
     * With step<=0 (sensitivity unavailable) the desired rotation is returned unchanged (pitch clamped).
     *
     * @return {@code [yaw, pitch]}
     */
    public static float[] normalize(float curYaw, float curPitch, float wantYaw, float wantPitch, double step) {
        if (step <= 0.0) return new float[]{ wantYaw, Mth.clamp(wantPitch, -90.0f, 90.0f) };
        float yaw = curYaw + snapDelta(angleDifference(wantYaw, curYaw), step);
        float pitch = Mth.clamp(curPitch + snapDelta(angleDifference(wantPitch, curPitch), step), -90.0f, 90.0f);
        return new float[]{ yaw, pitch };
    }

    /** Live per-unit step from the game options, mirroring the vanilla path (incl. scoping). 0 if unavailable. */
    public static double liveStep() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.options == null) return 0.0;
        Options opts = mc.options;
        double sens = opts.sensitivity().get();
        boolean scoped = !opts.smoothCamera
            && opts.getCameraType().isFirstPerson()
            && mc.player != null && mc.player.isScoping();
        return step(sens, scoped);
    }

    /**
     * Client-facing convenience: GCD-normalize {@code wanted} against {@code current} using the live
     * sensitivity step. Drop-in for the library's {@code normalizeToSensitivity(wanted, current)}.
     */
    public static AutismRotationUtil.Rotation normalize(AutismRotationUtil.Rotation current,
                                                        AutismRotationUtil.Rotation wanted) {
        float[] r = normalize(current.yaw(), current.pitch(), wanted.yaw(), wanted.pitch(), liveStep());
        return new AutismRotationUtil.Rotation(r[0], r[1]);
    }
}
