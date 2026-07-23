package com.boss.utility;

import com.boss.utility.util.NukerShape;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the pure Nuker decision helpers: sphere containment, the whitelist/blacklist rule (and its
 * conservative "Whitelist + empty ⇒ nothing" default), the Flatten height filter, and sort ordering.
 */
class NukerShapeTest {

    @Test
    void sphereContainmentUsesSquaredRadius() {
        assertTrue(NukerShape.inSphere(0, 0, 0, 4.0));
        assertTrue(NukerShape.inSphere(4, 0, 0, 4.0));      // exactly on the surface
        assertFalse(NukerShape.inSphere(5, 0, 0, 4.0));
        assertFalse(NukerShape.inSphere(3, 3, 3, 4.0));     // 27 > 16
        assertTrue(NukerShape.inSphere(2, 2, 2, 4.0));      // 12 <= 16
    }

    @Test
    void whitelistOnlyAllowsListedBlocks() {
        assertTrue(NukerShape.listAllows("Whitelist", true));
        assertFalse(NukerShape.listAllows("Whitelist", false));
    }

    @Test
    void blacklistAllowsEverythingExceptListed() {
        assertFalse(NukerShape.listAllows("Blacklist", true));
        assertTrue(NukerShape.listAllows("Blacklist", false));
    }

    @Test
    void conservativeDefaultBreaksNothing() {
        // Whitelist mode with an empty list ⇒ every block is "not in list" ⇒ never allowed.
        assertFalse(NukerShape.listAllows("Whitelist", false));
    }

    @Test
    void flattenBreaksAtOrAboveFeetOnly() {
        // Feet at y=64.0 (feet block 64): break 64 and above, keep the floor below.
        assertTrue(NukerShape.flattenAllows("Flatten", 64, 64.0));
        assertTrue(NukerShape.flattenAllows("Flatten", 70, 64.0));
        assertFalse(NukerShape.flattenAllows("Flatten", 63, 64.0));
        // "All" ignores height.
        assertTrue(NukerShape.flattenAllows("All", 10, 64.0));
    }

    @Test
    void flattenHandlesFractionalFeetY() {
        // Standing at y=64.9 → feet block is 64; y=64 still allowed.
        assertTrue(NukerShape.flattenAllows("Flatten", 64, 64.9));
        assertFalse(NukerShape.flattenAllows("Flatten", 63, 64.9));
    }

    @Test
    void sortScoreOrdersEachMode() {
        // Closest: ascending distance → nearer sorts first.
        assertTrue(NukerShape.sortScore("Closest", 4.0, 0) < NukerShape.sortScore("Closest", 9.0, 0));
        // Furthest: farther sorts first (smaller score).
        assertTrue(NukerShape.sortScore("Furthest", 9.0, 0) < NukerShape.sortScore("Furthest", 4.0, 0));
        // TopDown: higher Y sorts first (smaller score).
        assertTrue(NukerShape.sortScore("TopDown", 0.0, 70) < NukerShape.sortScore("TopDown", 0.0, 64));
        // None: everything equal (stable).
        assertEquals(0.0, NukerShape.sortScore("None", 123.0, 5));
    }
}
