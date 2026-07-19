package com.boss.pvp.util.pvp;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Memory of which mobs have recently attacked the local player, backing KillAura's "Retaliation"
 * activation mode: a mob only becomes a valid target after it has hit the player, and stays valid for a
 * configurable window (refreshed on every new hit) so it doesn't drop out of the target pool mid-fight.
 *
 * <p>Pure bookkeeping — no Minecraft deps — so the memory logic is directly unit-testable. The module feeds
 * it entity ids + timestamps; expiry is evaluated lazily on query and reclaimed by {@link #prune}.
 */
public final class RetaliationTracker {

    /** entityId -> last time (ms) that entity hit the player. */
    private final Map<Integer, Long> lastHitMs = new HashMap<>();

    /** Record that {@code entityId} hit the player at {@code nowMs}; refreshes an existing entry's window. */
    public void record(int entityId, long nowMs) {
        lastHitMs.put(entityId, nowMs);
    }

    /** True while {@code entityId}'s last recorded hit is within {@code windowMs} of {@code nowMs}. */
    public boolean isActive(int entityId, long nowMs, long windowMs) {
        Long t = lastHitMs.get(entityId);
        return t != null && nowMs - t <= windowMs;
    }

    /**
     * The activation decision for one candidate. Players are always eligible (Retaliation only restricts
     * mobs), Always mode is always eligible (no behavior change), and in Retaliation mode a mob is eligible
     * only while its memory window is active.
     */
    public static boolean shouldTarget(boolean retaliationMode, boolean isPlayer, boolean mobActive) {
        if (!retaliationMode || isPlayer) return true;
        return mobActive;
    }

    /** Drop entries older than {@code windowMs} so long sessions don't accumulate dead ids. */
    public void prune(long nowMs, long windowMs) {
        Iterator<Map.Entry<Integer, Long>> it = lastHitMs.entrySet().iterator();
        while (it.hasNext()) {
            if (nowMs - it.next().getValue() > windowMs) it.remove();
        }
    }

    public void clear() {
        lastHitMs.clear();
    }

    public int size() {
        return lastHitMs.size();
    }
}
