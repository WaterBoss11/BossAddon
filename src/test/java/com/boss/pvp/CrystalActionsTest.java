package com.boss.pvp;

import com.boss.pvp.util.pvp.CrystalActions;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the corrected AutoCrystal placement logic ({@link CrystalActions}): the placement box is the vanilla
 * 1×2×1 space above the base, any live entity in it blocks (crystals included — the old bug only checked
 * living entities in a 1×1×1 box), and a client-side-hidden crystal is treated as absent so its spot re-places.
 */
class CrystalActionsTest {

    private static final double EPS = 1e-9;

    @Test
    void placementBoxIsVanilla1x2x1AboveTheBase() {
        AABB box = CrystalActions.placementBox(new BlockPos(10, 64, -3));
        // 1 wide, 1 deep, 2 tall, sitting on top of the base block (y = 65..67).
        assertEquals(1.0, box.getXsize(), EPS);
        assertEquals(1.0, box.getZsize(), EPS);
        assertEquals(2.0, box.getYsize(), EPS);
        assertEquals(10.0, box.minX, EPS);
        assertEquals(-3.0, box.minZ, EPS);
        assertEquals(65.0, box.minY, EPS);   // one block above the base at y=64
        assertEquals(67.0, box.maxY, EPS);
    }

    @Test
    void anyLiveEntityBlocks() {
        // Non-crystal entity (player upper body, item, arrow…) present -> blocked. This is the 1×2×1 any-entity
        // correction: the old check missed all of these because they aren't LivingEntity or sat outside 1×1×1.
        assertTrue(CrystalActions.blocks(false, false, false));
    }

    @Test
    void liveVisibleCrystalBlocks() {
        // A real crystal occupying the spot blocks a new placement (vanilla rejects it) — the old check never
        // saw crystals at all (EndCrystal isn't a LivingEntity), so it wrongly placed into occupied spots.
        assertTrue(CrystalActions.blocks(false, true, false));
    }

    @Test
    void hiddenCrystalDoesNotBlock() {
        // The CrystalHide consumer: a crystal we hid client-side is logically dead, so its spot is free.
        assertFalse(CrystalActions.blocks(false, true, true));
    }

    @Test
    void removedEntityNeverBlocks() {
        assertFalse(CrystalActions.blocks(true, false, false));
        assertFalse(CrystalActions.blocks(true, true, false));
    }

    @Test
    void gateOpensOnDelayOrForce() {
        // Not enough time elapsed and no trigger -> closed.
        assertFalse(CrystalActions.gateOpen(false, 1_000L, 950L, 100L));
        // Enough time elapsed -> open.
        assertTrue(CrystalActions.gateOpen(false, 1_100L, 950L, 100L));
        // Exactly at the boundary -> open (>=).
        assertTrue(CrystalActions.gateOpen(false, 1_050L, 950L, 100L));
        // A trigger forces it open regardless of elapsed time.
        assertTrue(CrystalActions.gateOpen(true, 951L, 950L, 100L));
    }
}
