package com.boss.pvp.util.pvp;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * Client-side crystal hide + resync bookkeeping. Studied and rewritten from HCsCR's {@code HiddenEntities}
 * (Apache-2.0, © Offenderify / VidTu) — the technique, not the code.
 *
 * <p>When AutoCrystal confirms a break, it {@link #hide(int, int)}s the crystal for a resync window of N
 * ticks. A companion mixin returns an empty bounding box for any hidden crystal, so it drops out of the
 * client's entity raytrace/pick the instant we hit — before the server's removal round-trips.
 *
 * <p>The window is the anti-ghost safeguard: {@link #tick(IntPredicate)} runs once per client tick and, for
 * each hidden crystal, either (a) drops it if the server has confirmed the removal (stays gone), or (b)
 * counts down and un-hides it when the window elapses without confirmation — so a swing the server never
 * registered self-heals into a visible crystal again instead of leaving a permanent ghost.
 *
 * <p>State is static and thread-safe: crystals are hidden from the client tick thread, queried from render,
 * and confirmed-removed on the network thread.
 */
public final class CrystalHideManager {

    private CrystalHideManager() {}

    // entity id -> ticks remaining before the hitbox reappears
    private static final Map<Integer, Integer> HIDDEN = new ConcurrentHashMap<>();

    /** Hide a crystal for {@code ticks} client ticks. {@code ticks <= 0} hides for a single tick minimum. */
    public static void hide(int entityId, int ticks) {
        HIDDEN.put(entityId, Math.max(1, ticks));
    }

    /** Whether this entity id is currently hidden (its bounding box should read empty). */
    public static boolean isHidden(int entityId) {
        return HIDDEN.containsKey(entityId);
    }

    public static void unhide(int entityId) {
        HIDDEN.remove(entityId);
    }

    public static void clear() {
        HIDDEN.clear();
    }

    public static int hiddenCount() {
        return HIDDEN.size();
    }

    /**
     * Advance every hidden crystal by one tick. {@code serverRemoved} answers "has the server confirmed this
     * id is gone from the world?" — when true the crystal is dropped (stays gone); otherwise its window
     * counts down and it is un-hidden (reappears) once the window reaches zero.
     */
    public static void tick(IntPredicate serverRemoved) {
        if (HIDDEN.isEmpty()) return;
        for (Integer id : new ArrayList<>(HIDDEN.keySet())) {
            if (serverRemoved.test(id)) {
                HIDDEN.remove(id);              // server confirmed the kill -> keep it gone
                continue;
            }
            int left = HIDDEN.getOrDefault(id, 0) - 1;
            if (left <= 0) HIDDEN.remove(id);   // resync window elapsed with no confirmation -> reappear
            else HIDDEN.put(id, left);
        }
    }
}
