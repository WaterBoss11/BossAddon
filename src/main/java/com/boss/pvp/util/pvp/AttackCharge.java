package com.boss.pvp.util.pvp;

/**
 * Pure attack-strength charge math, mirroring vanilla {@code Player.getAttackStrengthScale} /
 * {@code getCurrentItemAttackStrengthDelay}, extracted here so it can be unit-tested without booting the client.
 *
 * <p>Vanilla model: a single per-player {@code attackStrengthTicker} counts up one per tick (reset to 0 on each
 * attack) and is <b>shared across held items</b>. The charge fraction is {@code ticker / delay}, where the delay
 * is {@code 20 / ATTACK_SPEED} of the <i>currently held</i> item. Because the ticker is shared but the delay is
 * per-item, the same ticker yields a different charge depending on which weapon is in hand when the hit lands.
 *
 * <p>AutoWeapon swaps to the highest-scoring weapon on the same tick as the attack. If that weapon is slower than
 * the one used to pass a "full charge" gate, the swing lands under-charged. {@link #bestWeaponCharged} recomputes
 * the gate against the weapon that will actually be swung.
 */
public final class AttackCharge {

    /** Ticks per second the vanilla ticker advances; delay is {@code TICKS_PER_SECOND / attackSpeed}. */
    private static final double TICKS_PER_SECOND = 20.0;

    private AttackCharge() {}

    /** Vanilla attack-strength delay in ticks for an ATTACK_SPEED attribute value: {@code 20 / attackSpeed}. */
    public static double attackSpeedToDelay(double attackSpeed) {
        if (attackSpeed <= 0.0) return Double.POSITIVE_INFINITY;
        return TICKS_PER_SECOND / attackSpeed;
    }

    /**
     * Unclamped charge fraction {@code ticker / delay}. Vanilla clamps this to {@code [0, 1]}; we keep it raw so
     * callers can compare against a threshold without the clamp hiding an over-full ticker. A non-positive delay
     * (bare hand / infinite attack speed) is treated as instantly charged.
     */
    public static double chargeScale(double ticker, double delay) {
        if (delay <= 0.0) return 1.0;
        return ticker / delay;
    }

    /** True once the ticker has reached the weapon's delay (i.e. a full-strength hit is available). */
    public static boolean isCharged(double ticker, double delay) {
        return chargeScale(ticker, delay) >= 1.0;
    }

    /**
     * Is the weapon AutoWeapon will swap to fully charged right now, given the charge state read from the
     * currently held item?
     *
     * @param currentScale clamped {@code getAttackStrengthScale(0)} of the currently held item, in {@code [0, 1]}
     * @param currentDelay attack-strength delay of the currently held item ({@code 20 / currentAttackSpeed})
     * @param bestDelay    attack-strength delay of the weapon AutoWeapon will swap to
     *
     * <p>While the current item is not yet saturated ({@code currentScale < 1}) the raw shared ticker is
     * recoverable <b>exactly</b> as {@code currentScale * currentDelay}, so the swap weapon's charge is computed
     * precisely. This is the common actively-fighting case (ticker is small between CPS-limited hits) and is
     * exactly where the under-charge bug bites — swapping a nearly-charged fast weapon for a slower one.
     *
     * <p>Once the current item is saturated ({@code currentScale >= 1}) the ticker is only known to be
     * {@code >= currentDelay}. If the swap weapon is faster it is certainly charged; if it is slower, saturating a
     * fast weapon takes &gt;0.6s of idling, by which point the slower weapon is charged too. We stay optimistic
     * there, which reproduces the legacy current-item {@code fullCharge} behaviour and avoids a hard stall when the
     * between-hits held weapon is permanently faster than the swap target. The narrow residual window
     * ({@code currentDelay <= ticker < bestDelay}) can only be closed by reading the raw ticker — see the mixin
     * follow-up noted in the research.
     */
    public static boolean bestWeaponCharged(double currentScale, double currentDelay, double bestDelay) {
        if (currentScale >= 1.0) return true;
        double ticker = currentScale * currentDelay;
        return isCharged(ticker, bestDelay);
    }
}
