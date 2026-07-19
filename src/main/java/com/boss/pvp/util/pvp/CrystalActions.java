package com.boss.pvp.util.pvp;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * Pure decision helpers for AutoCrystal placement/break, extracted so the logic is unit-testable without a
 * live client. The box math uses plain data types (BlockPos/AABB) that need no game bootstrap; the rest is
 * primitives read from the world.
 */
public final class CrystalActions {

    private CrystalActions() {}

    /**
     * The space a crystal placed on {@code base} would occupy: the vanilla 1×2×1 box starting at the block
     * above the base (obsidian/bedrock). Vanilla {@code EndCrystalItem.useOn} tests this box for entities.
     */
    public static AABB placementBox(BlockPos base) {
        return new AABB(base.above()).expandTowards(0.0, 1.0, 0.0);
    }

    /**
     * Whether an entity found inside a candidate crystal's placement box should block the placement.
     *
     * <p>Vanilla {@code EndCrystalItem.useOn} rejects a placement if <b>any</b> entity occupies the 1×2×1
     * space above the base (not just living entities, and crystals count). We add one exception: a crystal we
     * have hidden client-side ({@link CrystalHideManager}) is logically dead — its spot is free to re-place
     * into before the server's removal round-trips. Removed entities never block.
     *
     * @param removed        the entity is removed/dead
     * @param isCrystal      the entity is an end crystal
     * @param crystalHidden  the entity is a crystal currently hidden by CrystalHideManager
     */
    public static boolean blocks(boolean removed, boolean isCrystal, boolean crystalHidden) {
        if (removed) return false;
        if (isCrystal && crystalHidden) return false;
        return true;
    }

    /**
     * Whether an action's delay gate is open: either a trigger forced it, or {@code delayMs} has elapsed since
     * the last action. Break and place evaluate this independently now (place-after-break: a break no longer
     * forfeits the place phase in the same tick), so this pure form documents and tests that timing.
     */
    public static boolean gateOpen(boolean force, long nowMs, long lastActMs, long delayMs) {
        return force || nowMs - lastActMs >= delayMs;
    }
}
