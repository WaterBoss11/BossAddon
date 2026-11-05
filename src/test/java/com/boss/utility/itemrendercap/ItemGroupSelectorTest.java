/*
 * Copyright (c) 2026 Item Render Cap contributors
 * SPDX-License-Identifier: MIT
 * Ported from boss-optimizations/src/test/java/dev/bossoptimizations/itemrendercap/ItemGroupSelectorTest.java
 * Source commit: 6707d67b15741fe9c36d94a1356e7c78ef9d9780
 * Full license: META-INF/licenses/BossOptimizations-MIT.txt
 */
package com.boss.utility.itemrendercap;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the selector across simulated frames, including static-scene stability and selection
 * of new drops whose IDs are higher than the older items competing in their cells.
 */
class ItemGroupSelectorTest {
    private static final double CELL = 4.0D;

    private record Item(int id, double x, double y, double z) {
    }

    private static void offerAll(ItemGroupSelector selector, List<Item> items) {
        for (Item item : items) {
            selector.offer(item.id(), item.x(), item.y(), item.z());
        }
    }

    private static List<Item> pile(int firstId, int count, double x, double y, double z) {
        List<Item> items = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            items.add(new Item(firstId + i, x, y, z));
        }

        return items;
    }

    // ---------------------------------------------------------------------------------------
    // Bug 1: new drops were starved when entity IDs increased monotonically.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION: the old lowest-ID policy starves a new higher-ID drop in a full group")
    void oldLowestIdPolicyStarvesNewDrops() {
        // This reproduces the donor's shipped v1.0.0 behaviour to prove the root cause is real.
        IntArrayList kept = new IntArrayList();

        for (int id = 1; id <= 5; id++) {
            keepLowest(kept, id, 5);
        }

        keepLowest(kept, 9001, 5); // new drop with a higher ID than the existing pile

        assertFalse(kept.contains(9001),
                "old policy kept " + kept + " - the new drop is excluded, so its toss arc is never drawn");
        assertEquals(IntArrayList.of(1, 2, 3, 4, 5), kept);
    }

    @Test
    @DisplayName("FIX: the newest item wins a full-group slot when it has the highest ID")
    void newestItemAlwaysSelected() {
        ItemGroupSelector selector = new ItemGroupSelector();
        selector.begin(5, CELL);
        offerAll(selector, pile(1, 20, 0.5D, 64.0D, 0.5D));
        selector.offer(9001, 0.5D, 64.0D, 0.5D);
        selector.finish(ItemGroupSelector.NO_ID);

        assertTrue(selector.isVisible(9001), "the highest-ID new drop must render");
        assertEquals(5, selector.visibleCount());
    }

    @Test
    @DisplayName("FIX: a highest-ID tossed item stays visible on every frame of its arc over a full pile")
    void tossArcVisibleEveryFrame() {
        ItemGroupSelector selector = new ItemGroupSelector();

        // Two adjacent cells, both already saturated with older, lower-ID items.
        List<Item> cellA = pile(1, 20, 0.5D, 64.0D, 0.5D);
        List<Item> cellB = pile(100, 20, 5.5D, 64.0D, 0.5D);

        int frames = 40;

        for (int frame = 0; frame < frames; frame++) {
            // The thrown item flies from cell A across the boundary into cell B.
            double x = 0.5D + (7.0D * frame / (frames - 1));
            double y = 64.0D + Math.sin(Math.PI * frame / (frames - 1)) * 1.5D;

            selector.begin(5, CELL);
            offerAll(selector, cellA);
            offerAll(selector, cellB);
            selector.offer(9001, x, y, 0.5D);
            selector.finish(ItemGroupSelector.NO_ID);

            assertTrue(selector.isVisible(9001),
                    "thrown item vanished on frame " + frame + " at x=" + x + " - toss animation would be lost");
        }
    }

    // ---------------------------------------------------------------------------------------
    // Bug 2: frame-to-frame stability.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("STABILITY: a static scene with unchanged settings deselects nothing across 500 frames")
    void staticSceneIsPerfectlyStable() {
        ItemGroupSelector selector = new ItemGroupSelector();
        List<Item> items = scatter(400, 12345L);

        int totalDeselected = 0;
        int totalAppearedAfterFirstFrame = 0;

        for (int frame = 0; frame < 500; frame++) {
            selector.begin(5, CELL);
            offerAll(selector, items);
            selector.finish(ItemGroupSelector.NO_ID);

            if (frame > 0) {
                totalDeselected += selector.deselectedCount();
                totalAppearedAfterFirstFrame += selector.appearedCount();
            }
        }

        assertEquals(0, totalDeselected, "an unchanged static scene must never drop an item from the render set");
        assertEquals(0, totalAppearedAfterFirstFrame, "an unchanged static scene must never add an item either");
    }

    @Test
    @DisplayName("STABILITY: with a positive cap, no non-empty group selects zero items")
    void everyGroupAlwaysSelectsAtLeastOne() {
        ItemGroupSelector selector = new ItemGroupSelector();
        Random random = new Random(99L);

        for (int frame = 0; frame < 200; frame++) {
            List<Item> items = scatter(300, random.nextLong());

            selector.begin(5, CELL);
            offerAll(selector, items);
            selector.finish(ItemGroupSelector.NO_ID);

            assertEquals(0, selector.emptyGroupCount(), "frame " + frame + " produced an empty group");

            // Independently verify: every occupied cell has at least one visible member.
            Set<List<Long>> cells = new java.util.HashSet<>();
            Set<List<Long>> cellsWithVisible = new java.util.HashSet<>();

            for (Item item : items) {
                List<Long> key = List.of((long) Math.floor(item.x() / CELL),
                        (long) Math.floor(item.y() / CELL), (long) Math.floor(item.z() / CELL));
                cells.add(key);

                if (selector.isVisible(item.id())) {
                    cellsWithVisible.add(key);
                }
            }

            assertEquals(cells.size(), cellsWithVisible.size(),
                    "frame " + frame + ": some occupied cell rendered nothing at all");
        }
    }

    @Test
    @DisplayName("STABILITY: items jittering on a cell boundary - measured, not assumed")
    void boundaryJitterChurnIsBounded() {
        ItemGroupSelector selector = new ItemGroupSelector();

        // Ten items parked exactly on the x=4.0 cell boundary, wobbling a few centimetres.
        int frames = 600;
        int totalDeselected = 0;

        for (int frame = 0; frame < frames; frame++) {
            selector.begin(5, CELL);

            for (int i = 0; i < 10; i++) {
                double wobble = Math.sin((frame + i * 7) * 0.35D) * 0.05D;
                selector.offer(i + 1, 4.0D + wobble, 64.0D, 0.5D);
            }

            selector.finish(ItemGroupSelector.NO_ID);

            if (frame > 0) {
                totalDeselected += selector.deselectedCount();
            }
        }

        double perFrame = (double) totalDeselected / (frames - 1);
        System.out.printf("boundary-jitter churn: %d deselections over %d frames (%.4f per frame)%n",
                totalDeselected, frames - 1, perFrame);

        // This is residual, not zero: an item that physically crosses into a neighbouring cell
        // legitimately re-competes there. This fixture bounds churn for these moving items.
        assertTrue(perFrame < 1.0D,
                "boundary churn averaged " + perFrame + " deselections/frame, which would be visible flicker");
    }

    // ---------------------------------------------------------------------------------------
    // Crosshair exemption and cap behaviour.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("an offered crosshair target renders even when it would lose its group slot")
    void crosshairTargetAlwaysRenders() {
        ItemGroupSelector selector = new ItemGroupSelector();
        List<Item> items = pile(1, 50, 0.5D, 64.0D, 0.5D);
        selector.begin(5, CELL);
        offerAll(selector, items);
        selector.finish(3); // ID 3 is far from the highest and would otherwise be culled

        assertTrue(selector.isVisible(3), "offered crosshair target must render");
        assertEquals(6, selector.visibleCount(), "cap of 5 plus the forced crosshair target");
        assertEquals(44, selector.hiddenCount());
        assertEquals(5, selector.largestGroupSize(), "the crosshair exemption does not consume a group slot");
        for (int id = 46; id <= 50; id++) {
            assertTrue(selector.isVisible(id), "forcing the target must preserve the normal highest-ID winners");
        }

        selector.begin(5, CELL);
        offerAll(selector, items);
        selector.finish(50);

        assertEquals(5, selector.visibleCount(), "an already-selected target is not an extra item");
        assertEquals(45, selector.hiddenCount());
        assertTrue(selector.isVisible(50));
    }

    @Test
    @DisplayName("maxPerGroup = 0 hides everything except the offered crosshair target")
    void zeroCapHidesAllButCrosshair() {
        ItemGroupSelector selector = new ItemGroupSelector();
        selector.begin(0, CELL);
        offerAll(selector, pile(1, 30, 0.5D, 64.0D, 0.5D));
        selector.finish(7);

        assertEquals(1, selector.visibleCount());
        assertTrue(selector.isVisible(7));
    }

    @Test
    @DisplayName("the cap is applied per group, not globally")
    void capIsPerGroup() {
        ItemGroupSelector selector = new ItemGroupSelector();
        selector.begin(5, CELL);
        offerAll(selector, pile(1, 20, 0.5D, 64.0D, 0.5D));
        offerAll(selector, pile(100, 20, 100.5D, 64.0D, 0.5D));
        offerAll(selector, pile(200, 20, 200.5D, 64.0D, 0.5D));
        selector.finish(ItemGroupSelector.NO_ID);

        assertEquals(3, selector.groupCount());
        assertEquals(15, selector.visibleCount(), "three groups of five");
        assertEquals(45, selector.hiddenCount());
    }

    @Test
    @DisplayName("a group smaller than the cap renders in full")
    void smallGroupRendersEntirely() {
        ItemGroupSelector selector = new ItemGroupSelector();
        selector.begin(5, CELL);
        offerAll(selector, pile(1, 3, 0.5D, 64.0D, 0.5D));
        selector.finish(ItemGroupSelector.NO_ID);

        assertEquals(3, selector.visibleCount());
        assertEquals(0, selector.hiddenCount());
    }

    @Test
    @DisplayName("picking an item up does not count as instability")
    void despawnIsNotCountedAsChurn() {
        ItemGroupSelector selector = new ItemGroupSelector();
        List<Item> items = pile(1, 10, 0.5D, 64.0D, 0.5D);

        selector.begin(5, CELL);
        offerAll(selector, items);
        selector.finish(ItemGroupSelector.NO_ID);

        // Item 10 was visible (highest ID) and is now picked up.
        assertTrue(selector.isVisible(10));

        selector.begin(5, CELL);
        offerAll(selector, items.subList(0, 9));
        selector.finish(ItemGroupSelector.NO_ID);

        assertEquals(0, selector.deselectedCount(),
                "an item that no longer exists must not be reported as unstable selection");
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle, boundary, offer-order and input-validation regressions.
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("reset clears buckets, offered and visible IDs, visibility history and every metric")
    void resetClearsAllStateHistoryAndMetrics() {
        ItemGroupSelector selector = new ItemGroupSelector();
        List<Item> items = pile(1, 5, 0.5D, 64.0D, 0.5D);
        items.add(new Item(100, 100.5D, 64.0D, 0.5D));

        selector.begin(2, CELL);
        offerAll(selector, items);
        selector.finish(1);
        assertEquals(4, selector.appearedCount());

        selector.begin(1, CELL);
        offerAll(selector, items);
        selector.finish(3);
        assertEquals(2, selector.groupCount());
        assertEquals(6, selector.offeredCount());
        assertEquals(3, selector.visibleCount());
        assertEquals(3, selector.hiddenCount());
        assertEquals(1, selector.largestGroupSize());
        assertEquals(1, selector.appearedCount());
        assertEquals(2, selector.deselectedCount());

        selector.reset();

        assertEquals(0, selector.groupCount());
        assertEquals(0, selector.offeredCount());
        assertEquals(0, selector.visibleCount());
        assertEquals(0, selector.hiddenCount());
        assertEquals(0, selector.largestGroupSize());
        assertEquals(0, selector.appearedCount());
        assertEquals(0, selector.deselectedCount());
        assertEquals(0, selector.emptyGroupCount());
        for (Item item : items) {
            assertFalse(selector.isVisible(item.id()), "reset must forget every visible ID");
        }

        selector.begin(1, CELL);
        offerAll(selector, items);
        selector.finish(3);
        assertEquals(3, selector.appearedCount(), "the same selection is newly visible after reset");
        assertEquals(0, selector.deselectedCount(), "reset must discard the old visibility history");
    }

    @Test
    @DisplayName("an empty frame clears visibility and history without reporting despawns as churn")
    void emptyFrameClearsPreviousVisibility() {
        ItemGroupSelector selector = new ItemGroupSelector();
        List<Item> items = pile(1, 3, 0.5D, 64.0D, 0.5D);
        selector.begin(5, CELL);
        offerAll(selector, items);
        selector.finish(ItemGroupSelector.NO_ID);
        assertEquals(3, selector.visibleCount());

        selector.begin(5, CELL);
        selector.finish(ItemGroupSelector.NO_ID);

        assertEquals(0, selector.groupCount());
        assertEquals(0, selector.offeredCount());
        assertEquals(0, selector.visibleCount());
        assertEquals(0, selector.hiddenCount());
        assertEquals(0, selector.largestGroupSize());
        assertEquals(0, selector.appearedCount());
        assertEquals(0, selector.deselectedCount());
        assertEquals(0, selector.emptyGroupCount());
        for (Item item : items) {
            assertFalse(selector.isVisible(item.id()));
        }

        selector.begin(5, CELL);
        offerAll(selector, items);
        selector.finish(ItemGroupSelector.NO_ID);
        assertEquals(3, selector.appearedCount(), "an empty frame must replace the old visibility history");
    }

    @Test
    @DisplayName("a zero or negative cap with no crosshair target hides every offered item")
    void zeroCapWithoutCrosshairHidesEverything() {
        ItemGroupSelector selector = new ItemGroupSelector();
        List<Item> items = pile(1, 30, 0.5D, 64.0D, 0.5D);

        for (int cap : new int[]{0, -5}) {
            selector.begin(cap, CELL);
            offerAll(selector, items);
            selector.finish(ItemGroupSelector.NO_ID);

            assertEquals(30, selector.offeredCount());
            assertEquals(0, selector.visibleCount());
            assertEquals(30, selector.hiddenCount());
            assertEquals(0, selector.groupCount());
            assertEquals(0, selector.largestGroupSize());
            assertEquals(0, selector.appearedCount());
            assertEquals(0, selector.deselectedCount());
            assertEquals(0, selector.emptyGroupCount());
            for (Item item : items) {
                assertFalse(selector.isVisible(item.id()));
            }
        }
    }

    @Test
    @DisplayName("negative cell boundaries use floor, not truncation, on every axis")
    void negativeCellBoundariesUseFloorOnEveryAxis() {
        ItemGroupSelector selector = new ItemGroupSelector();
        double[] coordinates = {-4.0001D, -4.0D, -0.0001D, 0.0D, 3.9999D, 4.0D};
        Set<Integer> expected = Set.of(1, 3, 5, 6);

        for (int axis = 0; axis < 3; axis++) {
            selector.begin(1, CELL);
            for (int i = 0; i < coordinates.length; i++) {
                double[] position = {0.5D, 0.5D, 0.5D};
                position[axis] = coordinates[i];
                selector.offer(i + 1, position[0], position[1], position[2]);
            }
            selector.finish(ItemGroupSelector.NO_ID);

            // Cells [-8,-4), [-4,0), [0,4), [4,8) keep IDs 1, 3, 5 and 6 respectively.
            assertEquals(4, selector.groupCount(), "axis " + axis);
            assertEquals(6, selector.offeredCount());
            assertEquals(4, selector.visibleCount());
            assertEquals(2, selector.hiddenCount());
            assertEquals(1, selector.largestGroupSize());
            for (int id = 1; id <= coordinates.length; id++) {
                assertEquals(expected.contains(id), selector.isVisible(id), "axis " + axis + ", ID " + id);
            }
        }
    }

    @Test
    @DisplayName("reversed and shuffled offers keep the same highest IDs with no static-scene churn")
    void offerOrderDoesNotChangeHighestIdsOrChurn() {
        ItemGroupSelector selector = new ItemGroupSelector();
        List<Item> items = pile(1, 40, 0.5D, 64.0D, 0.5D);
        items.addAll(pile(101, 40, 100.5D, 64.0D, 0.5D));
        Set<Integer> expected = Set.of(36, 37, 38, 39, 40, 136, 137, 138, 139, 140);
        Random random = new Random(7239L);

        for (int frame = 0; frame < 12; frame++) {
            if (frame == 1) {
                Collections.reverse(items);
            } else if (frame > 1) {
                Collections.shuffle(items, random);
            }

            selector.begin(5, CELL);
            offerAll(selector, items);
            selector.finish(ItemGroupSelector.NO_ID);

            assertEquals(2, selector.groupCount());
            assertEquals(80, selector.offeredCount());
            assertEquals(10, selector.visibleCount());
            assertEquals(70, selector.hiddenCount());
            assertEquals(5, selector.largestGroupSize());
            assertEquals(frame == 0 ? 10 : 0, selector.appearedCount());
            assertEquals(0, selector.deselectedCount());
            for (Item item : items) {
                assertEquals(expected.contains(item.id()), selector.isVisible(item.id()),
                        "frame " + frame + ", ID " + item.id());
            }
        }
    }

    @Test
    @DisplayName("zero and negative cell widths are rejected")
    void invalidCellWidthsAreRejected() {
        ItemGroupSelector selector = new ItemGroupSelector();

        for (double width : new double[]{0.0D, -0.0D, -1.0D, -Double.MIN_VALUE, -Double.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> selector.begin(5, width), "width " + width);
        }
    }

    @Test
    @DisplayName("NaN and infinite cell widths are rejected")
    void nonfiniteCellWidthsAreRejected() {
        ItemGroupSelector selector = new ItemGroupSelector();

        for (double width : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> selector.begin(5, width), "width " + width);
        }
    }

    @Test
    @DisplayName("finite positive cell widths, including the smallest and largest doubles, remain valid")
    void finitePositiveCellWidthsAreAccepted() {
        ItemGroupSelector selector = new ItemGroupSelector();

        for (double width : new double[]{Double.MIN_VALUE, 0.25D, CELL, Double.MAX_VALUE}) {
            selector.begin(1, width);
            selector.offer(1, 0.0D, 0.0D, 0.0D);
            selector.finish(ItemGroupSelector.NO_ID);

            assertEquals(1, selector.groupCount());
            assertEquals(1, selector.offeredCount());
            assertEquals(1, selector.visibleCount());
            assertEquals(0, selector.hiddenCount());
            assertTrue(selector.isVisible(1));
        }
    }

    @Test
    @DisplayName("a forced ID absent from the current offers cannot create visibility")
    void absentForcedTargetIsIgnored() {
        ItemGroupSelector selector = new ItemGroupSelector();
        List<Item> items = pile(1, 3, 0.5D, 64.0D, 0.5D);

        for (int cap : new int[]{0, 2}) {
            selector.begin(cap, CELL);
            offerAll(selector, items);
            selector.finish(999);

            assertFalse(selector.isVisible(999), "a crosshair ID must be offered in the current frame");
            assertEquals(3, selector.offeredCount());
            assertEquals(cap, selector.visibleCount());
            assertEquals(3 - cap, selector.hiddenCount());
            assertEquals(cap, selector.appearedCount());
            assertEquals(0, selector.deselectedCount());
            assertFalse(selector.isVisible(1));
            assertEquals(cap > 0, selector.isVisible(2));
            assertEquals(cap > 0, selector.isVisible(3));
        }
    }

    @Test
    @DisplayName("a vanished crosshair target cannot leak into the next frame or make hidden counts negative")
    void vanishedCrosshairTargetDoesNotLeakAcrossFrames() {
        ItemGroupSelector selector = new ItemGroupSelector();

        for (int cap : new int[]{0, 2}) {
            selector.begin(cap, CELL);
            selector.offer(7, 0.5D, 64.0D, 0.5D);
            selector.finish(7);
            assertTrue(selector.isVisible(7));
            assertEquals(1, selector.visibleCount());
            assertEquals(0, selector.hiddenCount());

            selector.begin(cap, CELL);
            selector.finish(7); // stale crosshair ID after the item disappeared

            assertFalse(selector.isVisible(7));
            assertEquals(0, selector.groupCount());
            assertEquals(0, selector.offeredCount());
            assertEquals(0, selector.visibleCount());
            assertEquals(0, selector.hiddenCount(), "an absent target must not produce a negative count");
            assertEquals(0, selector.appearedCount());
            assertEquals(0, selector.deselectedCount());
            assertEquals(0, selector.emptyGroupCount());
        }
    }

    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("REGRESSION: mirrored cells must not share a cap through a hash collision")
    void distinctMirroredCellsNeverShareTheirCap() {
        ItemGroupSelector selector = new ItemGroupSelector();
        selector.begin(1, CELL);
        selector.offer(1, 4.5, 64.5, 4.5);     // cell (1, 16, 1)
        selector.offer(2, -0.5, 64.5, -0.5);   // cell (-1, 16, -1); same old lossy hash
        selector.finish(ItemGroupSelector.NO_ID);
        assertEquals(2, selector.groupCount(), "Distinct coordinate tuples must be distinct groups");
        assertEquals(2, selector.visibleCount());
        assertTrue(selector.isVisible(1));
        assertTrue(selector.isVisible(2));
    }

    private static List<Item> scatter(int count, long seed) {
        Random random = new Random(seed);
        List<Item> items = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            items.add(new Item(i + 1,
                    random.nextInt(60) + 0.5D,
                    64.0D,
                    random.nextInt(60) + 0.5D));
        }

        return items;
    }

    /** The donor's shipped v1.0.0 policy, kept only so the regression test can demonstrate the bug. */
    private static void keepLowest(IntArrayList kept, int id, int max) {
        int size = kept.size();

        if (size >= max && id >= kept.getInt(size - 1)) {
            return;
        }

        int index = 0;

        while (index < size && kept.getInt(index) < id) {
            index++;
        }

        kept.add(index, id);

        if (kept.size() > max) {
            kept.removeInt(kept.size() - 1);
        }
    }
}
