/*
 * Copyright (c) 2026 Item Render Cap contributors
 * SPDX-License-Identifier: MIT
 * Ported from boss-optimizations/src/main/java/dev/bossoptimizations/itemrendercap/ItemGroupSelector.java
 * Source commit: 6707d67b15741fe9c36d94a1356e7c78ef9d9780
 * Full license: META-INF/licenses/BossOptimizations-MIT.txt
 */
package com.boss.utility.itemrendercap;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * The grouping and selection logic, deliberately free of any Minecraft types so it can be
 * exercised directly by tests across simulated frames. A rendering adapter feeds real entities
 * into this.
 *
 * <p>Usage per frame: {@link #begin}, then {@link #offer} for every item entity, then
 * {@link #finish}. After that {@link #isVisible} answers for the whole frame.
 *
 * <h2>Why highest ID wins</h2>
 * When entity IDs increase monotonically, a newly dropped item has a higher ID than older
 * items. Selecting the <em>lowest</em> IDs can therefore starve new drops in a group that is
 * already full, hiding their toss arc until an older item leaves the group. Selecting the
 * <em>highest</em> IDs instead gives the highest-ID drops priority over the older settled pile.
 * This corresponds to newest-first selection only while IDs increase monotonically; it is not
 * an age guarantee across ID wraparound or reassignment.
 *
 * <p>ID ordering makes selection stable in a static scene with unchanged IDs, cell membership,
 * cap and crosshair target, independently of offer order or camera distance. Moving between
 * cells, new drops, or changes to the cap or target can still change the selected set.
 */
public final class ItemGroupSelector {
    /** Sentinel for "no crosshair target this frame". */
    public static final int NO_ID = -1;

    // Hashes locate buckets; equality on the full tuple determines cell identity.
    private record Cell(long x, long y, long z) { }
    private final Map<Cell, IntArrayList> buckets = new HashMap<>();
    private final IntOpenHashSet visible = new IntOpenHashSet();
    private final IntOpenHashSet previousVisible = new IntOpenHashSet();
    private final IntOpenHashSet offered = new IntOpenHashSet();

    private int maxPerGroup;
    private double cellSize;

    // Diagnostics for the last completed frame.
    private int appearedCount;
    private int deselectedCount;
    private int emptyGroupCount;

    public void begin(int maxPerGroup, double cellSize) {
        if (!Double.isFinite(cellSize) || cellSize <= 0.0D) {
            throw new IllegalArgumentException("cellSize must be finite and positive, got " + cellSize);
        }

        this.maxPerGroup = Math.max(0, maxPerGroup);
        this.cellSize = cellSize;

        buckets.clear();
        offered.clear();
    }

    public void offer(int id, double x, double y, double z) {
        offered.add(id);

        if (maxPerGroup <= 0) {
            return;
        }

        Cell key = new Cell((long) Math.floor(x / cellSize), (long) Math.floor(y / cellSize),
                (long) Math.floor(z / cellSize));
        IntArrayList kept = buckets.get(key);

        if (kept == null) {
            kept = new IntArrayList(maxPerGroup + 1);
            buckets.put(key, kept);
        }

        keepHighest(kept, id, maxPerGroup);
    }

    /**
     * @param forcedId an entity offered this frame that must render in addition to its group's
     *                 selection if necessary, or {@link #NO_ID}; absent IDs are ignored.
     */
    public void finish(int forcedId) {
        visible.clear();
        emptyGroupCount = 0;

        Iterator<IntArrayList> groups = buckets.values().iterator();

        while (groups.hasNext()) {
            IntArrayList kept = groups.next();

            // A non-empty group must always contribute at least one item. If this ever trips,
            // the selection logic is dropping whole groups - exactly the failure mode where
            // items "should be in a rendered group" but never appear.
            if (kept.isEmpty()) {
                emptyGroupCount++;
                continue;
            }

            visible.addAll(kept);
        }

        if (forcedId != NO_ID && offered.contains(forcedId)) {
            visible.add(forcedId);
        }

        computeChurn();

        previousVisible.clear();
        previousVisible.addAll(visible);
    }

    /**
     * Splits visibility changes into two cases:
     * <ul>
     * <li><b>appeared</b> - newly visible (for example, new drops or items entering view).
     * <li><b>deselected</b> - the item was offered this frame but stopped being drawn. This
     * measures selection churn, which should be zero in a static scene with unchanged IDs,
     * cell membership, cap and crosshair target. Changes to those inputs can cause churn.
     * </ul>
     */
    private void computeChurn() {
        appearedCount = 0;
        deselectedCount = 0;

        IntIterator current = visible.iterator();

        while (current.hasNext()) {
            if (!previousVisible.contains(current.nextInt())) {
                appearedCount++;
            }
        }

        IntIterator previous = previousVisible.iterator();

        while (previous.hasNext()) {
            int id = previous.nextInt();

            // Only count it as instability if the entity is still here. An item that was picked
            // up or despawned legitimately stops rendering and is not churn.
            if (offered.contains(id) && !visible.contains(id)) {
                deselectedCount++;
            }
        }
    }

    public boolean isVisible(int id) {
        return visible.contains(id);
    }

    public int groupCount() {
        return buckets.size();
    }

    public int offeredCount() {
        return offered.size();
    }

    public int visibleCount() {
        return visible.size();
    }

    public int hiddenCount() {
        return offered.size() - visibleCount();
    }

    /** Items newly drawn this frame that were not drawn last frame. */
    public int appearedCount() {
        return appearedCount;
    }

    /** Items still offered but no longer drawn; zero for a static scene with unchanged inputs. */
    public int deselectedCount() {
        return deselectedCount;
    }

    /** Non-empty groups that contributed nothing. Should always be 0. */
    public int emptyGroupCount() {
        return emptyGroupCount;
    }

    /** Largest group size before the cap was applied is not tracked; this is post-cap. */
    public int largestGroupSize() {
        int largest = 0;
        Iterator<IntArrayList> groups = buckets.values().iterator();

        while (groups.hasNext()) {
            largest = Math.max(largest, groups.next().size());
        }

        return largest;
    }

    public void reset() {
        buckets.clear();
        visible.clear();
        previousVisible.clear();
        offered.clear();
        appearedCount = 0;
        deselectedCount = 0;
        emptyGroupCount = 0;
    }

    /** Inserts {@code id} into a descending list, keeping at most {@code max} entries. */
    static void keepHighest(IntArrayList kept, int id, int max) {
        int size = kept.size();

        // List is descending, so the last element is the smallest one currently kept.
        if (size >= max && id <= kept.getInt(size - 1)) {
            return;
        }

        int index = 0;

        while (index < size && kept.getInt(index) > id) {
            index++;
        }

        kept.add(index, id);

        if (kept.size() > max) {
            kept.removeInt(kept.size() - 1);
        }
    }

}
