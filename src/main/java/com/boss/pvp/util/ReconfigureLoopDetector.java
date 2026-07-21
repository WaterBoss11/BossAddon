package com.boss.pvp.util;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Pure sliding-window detector for a reconfigure loop: counts how many play&rarr;configuration transitions have
 * happened within a recent time window. {@link #record} returns true once the count reaches the threshold, so a
 * single legitimate reconfigure (e.g. a server-side resource-pack reload) never trips it, while a rapid repeated
 * loop does. No Minecraft types — unit-testable with an injected clock.
 */
public final class ReconfigureLoopDetector {

    private final int threshold;
    private final long windowMs;
    private final Deque<Long> events = new ArrayDeque<>();

    public ReconfigureLoopDetector(int threshold, long windowMs) {
        this.threshold = threshold;
        this.windowMs = windowMs;
    }

    /**
     * Record a reconfigure at {@code nowMs}, drop any events older than the window, and report whether the
     * number of events still within the window has reached the trigger threshold.
     */
    public boolean record(long nowMs) {
        events.addLast(nowMs);
        while (!events.isEmpty() && nowMs - events.peekFirst() > windowMs) {
            events.removeFirst();
        }
        return events.size() >= threshold;
    }

    /** Number of events currently within the window (for diagnostics / testing). */
    public int windowCount() {
        return events.size();
    }

    /** Forget all recorded events (called after a trigger so the same burst can't re-fire). */
    public void reset() {
        events.clear();
    }
}
