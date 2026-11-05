package com.boss.utility.itemrendercap;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.*;

class ItemRenderFrameTest {
    private final ItemRenderFrame frame = new ItemRenderFrame();
    private final Object world = new Object();
    private final AtomicInteger builds = new AtomicInteger();

    private ToIntFunction<ItemGroupSelector> pile(int count) {
        return selector -> {
            builds.incrementAndGet();
            for (int id = 1; id <= count; id++) selector.offer(id, 0.1, 64.1, 0.1);
            return ItemGroupSelector.NO_ID;
        };
    }

    private boolean allow(int id) { return frame.allow(world, true, true, false, id); }

    @Test void queriesNeverBuildAndAnswersStayFixedUntilNextFrame() {
        assertTrue(allow(1), "no frame yet: leave vanilla alone");
        frame.begin(world, true, true, false, 2, 4, pile(10));
        for (int i = 0; i < 500; i++) {
            assertFalse(allow(1));
            assertTrue(allow(10));
            assertFalse(allow(11), "a new item waits until the next extraction boundary");
        }
        assertEquals(1, builds.get());
        frame.begin(world, true, true, false, 2, 4, pile(11));
        assertTrue(allow(11));
        assertEquals(2, builds.get());
    }

    @Test void settingsChangeOnlyAtFrameBoundary() {
        frame.begin(world, true, true, false, 1, 4, pile(10));
        assertFalse(allow(9));
        frame.begin(world, true, true, false, 2, 4, pile(10));
        assertTrue(allow(9));
    }

    @Test void everyInertGateAllowsVanillaAndPerformsZeroCollection() {
        for (boolean module : new boolean[]{false, true}) {
            for (boolean utility : new boolean[]{false, true}) {
                for (boolean donor : new boolean[]{false, true}) {
                    if (module && utility && !donor) continue;
                    frame.begin(world, module, utility, donor, 0, 4, pile(10));
                    assertTrue(frame.allow(world, module, utility, donor, 1));
                }
            }
        }
        frame.begin(null, true, true, false, 0, 4, pile(10));
        assertEquals(0, builds.get());
    }

    @Test void disablingEachGateTakesEffectImmediatelyWithoutARebuild() {
        frame.begin(world, true, true, false, 0, 4, pile(10));
        assertFalse(allow(1));
        assertTrue(frame.allow(world, false, true, false, 1));
        assertTrue(frame.allow(world, true, false, false, 1));
        assertTrue(frame.allow(world, true, true, true, 1));
        assertEquals(1, builds.get());
    }

    @Test void lifecycleResetAllowsVanillaUntilNextFrame() {
        frame.begin(world, true, true, false, 0, 4, pile(10));
        frame.reset();
        assertTrue(allow(1));
        frame.begin(world, true, true, false, 10, 4, pile(10));
        assertTrue(allow(1));
    }

    @Test void disconnectAndReusedIdsInAnotherWorldCannotInheritSelection() {
        frame.begin(world, true, true, false, 0, 4, pile(10));
        Object otherWorld = new Object();
        assertTrue(frame.allow(otherWorld, true, true, false, 1));
        frame.begin(null, true, true, false, 0, 4, pile(10));
        assertTrue(allow(1));
        frame.begin(otherWorld, true, true, false, 10, 4, pile(10));
        assertTrue(frame.allow(otherWorld, true, true, false, 1));
        assertEquals(2, builds.get());
    }

    @Test void emptyWorldFrameReplacesTheOldSelection() {
        frame.begin(world, true, true, false, 5, 4, pile(3));
        frame.begin(world, true, true, false, 5, 4, pile(0));
        assertFalse(allow(3));
    }

    @Test void capsAreBoundedEvenForHandEditedSettings() {
        frame.begin(world, true, true, false, -1, 4, pile(200));
        assertFalse(allow(200));
        frame.begin(world, true, true, false, Integer.MAX_VALUE, 4, pile(200));
        assertFalse(allow(72));
        assertTrue(allow(73));
    }

    @Test void cellWidthsClampAndNonfiniteWidthsFallBackToFour() {
        ToIntFunction<ItemGroupSelector> pair = selector -> {
            selector.offer(1, 0.1, 64.1, 0.1);
            selector.offer(2, 1.1, 64.1, 0.1);
            return ItemGroupSelector.NO_ID;
        };
        frame.begin(world, true, true, false, 1, -1, pair);
        assertTrue(allow(1), "minimum 0.5 width separates the two items");
        for (double width : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE}) {
            frame.begin(world, true, true, false, 1, width, pair);
            assertFalse(allow(1));
            assertTrue(allow(2));
        }
    }

    @Test void donorArrivingAtBoundaryClearsExistingSelectionWithoutCollection() {
        frame.begin(world, true, true, false, 0, 4, pile(10));
        frame.begin(world, true, true, true, 0, 4, pile(10));
        assertTrue(allow(1), "cleared selection remains inert even if a gate is checked independently");
        assertEquals(1, builds.get());
    }
}
