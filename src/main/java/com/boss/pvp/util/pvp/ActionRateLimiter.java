package com.boss.pvp.util.pvp;

import java.util.ArrayDeque;

/**
 * Sliding-window rate limiter (max N actions per second), used to cap AutoCrystal break attempts across both
 * the per-tick loop and the crystal-spawn fast-break path so the two cooperate under one honest cap instead
 * of the old fixed "1 per 2 ticks" throttle. Pure and unit-testable; callers pass {@code nowMs}.
 *
 * <p>Not thread-safe on its own — AutoCrystal calls it only from the client thread (tick + entity-load hook).
 */
public final class ActionRateLimiter {

    private final ArrayDeque<Long> stamps = new ArrayDeque<>();

    /**
     * Try to consume one action slot at {@code nowMs}. Returns true (and records it) if fewer than
     * {@code maxPerSecond} actions occurred in the trailing 1000 ms; false if the cap is reached.
     * A non-positive {@code maxPerSecond} always denies.
     */
    public boolean tryAcquire(long nowMs, int maxPerSecond) {
        if (maxPerSecond <= 0) return false;
        evict(nowMs);
        if (stamps.size() >= maxPerSecond) return false;
        stamps.addLast(nowMs);
        return true;
    }

    /** How many actions are recorded within the trailing second ending at {@code nowMs}. */
    public int countWithinLastSecond(long nowMs) {
        evict(nowMs);
        return stamps.size();
    }

    public void clear() {
        stamps.clear();
    }

    private void evict(long nowMs) {
        while (!stamps.isEmpty() && nowMs - stamps.peekFirst() >= 1000L) stamps.pollFirst();
    }
}
