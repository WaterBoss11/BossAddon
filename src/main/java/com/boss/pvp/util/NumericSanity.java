package com.boss.pvp.util;

/**
 * Shared numeric sanity checks for incoming packet data — a single place that decides whether a raw {@code double}
 * or {@code float} the server sent is safe to feed into vanilla's own position/velocity/section math, or whether
 * it is malformed (NaN / infinite) or absurdly large enough to overflow that math and crash the client.
 *
 * <p>This generalises the original velocity crash guard: rather than a one-off fix per exploit packet, any
 * incoming packet handler can call these predicates on the fields it is about to apply and reject or clamp a bad
 * value the same way. Real overflow-crash reports carried per-axis values around {@code 1.8e38 / 2.8e38 / 2.1e38}
 * (near {@code Float.MAX_VALUE}); every guard here sits many orders of magnitude below that and far above any
 * legitimate value.
 *
 * <p><b>Two ceilings, one reasoning</b> — "well above anything legitimate, far below the values that overflow":
 * <ul>
 *   <li>{@link #SANE_LIMIT} ({@code 1e9}) — the general ceiling for a position or velocity component the client
 *       will apply. Vanilla's world border caps coordinates near {@code 3.0e7} and real per-tick speeds stay well
 *       under ~100, so {@code 1e9} clears every legitimate value by a wide margin while staying ~29 orders of
 *       magnitude under the crash values.</li>
 *   <li>{@link #MAX_MOTION_PER_AXIS} ({@code 1e4}) — the tighter cap the velocity crash guard uses when it
 *       <em>clamps</em> a single motion axis (keeping direction at a sane magnitude) instead of dropping the
 *       update. Still enormously above any real speed.</li>
 * </ul>
 *
 * <p>Purely defensive: these never alter anything sent to the server and never fabricate acknowledgements — they
 * only let a handler avoid processing a value that would crash the local client. No Minecraft types, so the whole
 * decision surface is unit-testable without a client.
 */
public final class NumericSanity {

    private NumericSanity() {}

    /** General ceiling for a position/velocity component the client applies (blocks or blocks/tick). */
    public static final double SANE_LIMIT = 1.0e9;

    /** Tighter per-axis velocity cap used when clamping motion to a sane magnitude rather than dropping it. */
    public static final double MAX_MOTION_PER_AXIS = 1.0e4;

    /** True when {@code v} is NaN or infinite — i.e. not a usable finite number. */
    public static boolean isNonFinite(double v) {
        return Double.isNaN(v) || Double.isInfinite(v);
    }

    /** Float overload of {@link #isNonFinite(double)}. */
    public static boolean isNonFinite(float v) {
        return Float.isNaN(v) || Float.isInfinite(v);
    }

    /** True when {@code v} is non-finite, or its magnitude exceeds {@code limit} — unsafe to feed to vanilla math. */
    public static boolean isInsane(double v, double limit) {
        return isNonFinite(v) || Math.abs(v) > limit;
    }

    /** Float overload of {@link #isInsane(double, double)}. */
    public static boolean isInsane(float v, double limit) {
        return isNonFinite(v) || Math.abs((double) v) > limit;
    }

    /** True when any of {@code x/y/z} is insane against {@link #SANE_LIMIT} — the check every position/velocity
     * packet path uses before letting the client apply the triple. */
    public static boolean isInsane(double x, double y, double z) {
        return isInsane(x, SANE_LIMIT) || isInsane(y, SANE_LIMIT) || isInsane(z, SANE_LIMIT);
    }

    /**
     * Clamp {@code v} into {@code [-limit, limit]}: {@code NaN} becomes {@code nanFallback} (no direction to
     * preserve), {@code +/-Infinity} and any finite value past the cap become {@code +/-limit}, and every value
     * already in range is returned bit-for-bit unchanged.
     */
    public static double clamp(double v, double limit, double nanFallback) {
        if (Double.isNaN(v)) return nanFallback;
        if (v > limit) return limit;      // also catches +Infinity
        if (v < -limit) return -limit;    // also catches -Infinity
        return v;
    }

    /** Clamp one incoming velocity axis to {@link #MAX_MOTION_PER_AXIS} (NaN &rarr; 0), keeping in-range values
     * untouched. This is the exact function the motion-packet crash guard applies. */
    public static double clampMotion(double v) {
        return clamp(v, MAX_MOTION_PER_AXIS, 0.0);
    }
}
