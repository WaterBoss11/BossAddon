package com.boss.utility.util;

/**
 * Pure decision helpers for {@link com.boss.utility.module.world.NukerModule}, extracted so the shape test,
 * whitelist/blacklist rule, flatten filter and sort ordering are unit-testable without a live client (the
 * module itself needs a world, but these are primitives).
 *
 * <p>Studied and rewritten from Meteor Client's Nuker (https://github.com/MeteorDevelopment/meteor-client,
 * GPL-3.0). BossAddon is likewise GPL-3.0.
 */
public final class NukerShape {

    private NukerShape() {}

    /** Whether an offset from the player is inside the Sphere shape of the given radius (squared compare). */
    public static boolean inSphere(int dx, int dy, int dz, double range) {
        return (double) dx * dx + (double) dy * dy + (double) dz * dz <= range * range;
    }

    /**
     * Whether a block is allowed by the current list mode. Whitelist: only blocks in the list are allowed;
     * Blacklist: every block except those in the list is allowed. Mirrors CrystalActions-style pure gates.
     *
     * <p>Note the deliberately conservative default the module ships: Whitelist + empty list ⇒ {@code inList}
     * is always false ⇒ nothing is ever allowed, so Nuker breaks nothing until you add blocks. (Meteor
     * defaults to Blacklist + empty ⇒ breaks everything.)
     */
    public static boolean listAllows(String listMode, boolean inList) {
        return "Whitelist".equals(listMode) ? inList : !inList;
    }

    /**
     * Flatten filter: in Flatten mode only blocks at or above the player's feet are broken (so you carve down
     * to a walkable level without dropping the floor out from under you). "All" allows any height.
     */
    public static boolean flattenAllows(String mode, int blockY, double feetY) {
        if (!"Flatten".equals(mode)) return true;
        return blockY >= (int) Math.floor(feetY);
    }

    /**
     * Sort score for ordering candidate blocks; the caller sorts ascending by this value. Closest → nearest
     * first, Furthest → farthest first, TopDown → highest first, None → stable (all equal).
     */
    public static double sortScore(String sortMode, double distSq, int blockY) {
        return switch (sortMode) {
            case "Closest" -> distSq;
            case "Furthest" -> -distSq;
            case "TopDown" -> -blockY;
            default -> 0.0;
        };
    }
}
