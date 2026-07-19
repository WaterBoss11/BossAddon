package com.boss.pvp.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tests the pure version-comparison logic behind the launch-time update check ({@link VersionCompare}). */
class VersionCompareTest {

    @Test
    void normalizeStripsLeadingVAndWhitespace() {
        assertEquals("1.2.3", VersionCompare.normalize("v1.2.3"));
        assertEquals("1.2.3", VersionCompare.normalize("V1.2.3"));
        assertEquals("1.2.3", VersionCompare.normalize("  1.2.3  "));
        assertEquals("", VersionCompare.normalize(null));
    }

    @Test
    void compareOrdersNumerically() {
        assertTrue(VersionCompare.compare("1.0.0", "1.0.1") < 0);
        assertTrue(VersionCompare.compare("1.0.1", "1.0.0") > 0);
        assertEquals(0, VersionCompare.compare("1.2.0", "1.2.0"));
    }

    @Test
    void compareIsNotLexical() {
        // The classic trap: "1.9.0" is OLDER than "1.10.0" even though '9' > '1' lexically.
        assertTrue(VersionCompare.compare("1.9.0", "1.10.0") < 0);
        assertTrue(VersionCompare.compare("1.10.0", "1.9.0") > 0);
    }

    @Test
    void missingSegmentsCountAsZero() {
        assertEquals(0, VersionCompare.compare("1.2", "1.2.0"));
        assertTrue(VersionCompare.compare("1.2", "1.2.1") < 0);
    }

    @Test
    void ignoresLeadingVOnEitherSide() {
        assertEquals(0, VersionCompare.compare("v1.4.0", "1.4.0"));
        assertTrue(VersionCompare.isOutdated("1.4.0", "v1.5.0"));
    }

    @Test
    void preReleaseSuffixComparesByLeadingInteger() {
        assertEquals(0, VersionCompare.compare("1.2.0-rc1", "1.2.0"));
        assertTrue(VersionCompare.compare("1.2.0", "1.3.0-beta") < 0);
    }

    @Test
    void isOutdatedTrueOnlyWhenStrictlyBehind() {
        assertTrue(VersionCompare.isOutdated("1.0.0", "1.9.0"));
        assertFalse(VersionCompare.isOutdated("1.9.0", "1.9.0"));   // equal
        assertFalse(VersionCompare.isOutdated("2.0.0", "1.9.0"));   // ahead (dev build)
    }

    @Test
    void bakedVersionMatchingReleaseTagIsNotOutdated() {
        // Regression for the versioning fix: the jar is now baked at 1.9.1 and the GitHub release is tagged
        // "v1.9.1". The tag's leading "v" must be ignored so an up-to-date client never sees a false notice.
        assertFalse(VersionCompare.isOutdated("1.9.1", "v1.9.1"));
        assertFalse(VersionCompare.isOutdated("1.9.1", "1.9.1"));
        // And the old broken state (jar stuck at 1.0.0 while the tag advanced) WOULD have flagged — proving
        // the check itself was correct and the baked version was the bug.
        assertTrue(VersionCompare.isOutdated("1.0.0", "v1.9.1"));
    }

    @Test
    void isOutdatedFalseOnUnusableInput() {
        // Blank/garbage on either side must never fire a false "out of date" notice.
        assertFalse(VersionCompare.isOutdated("", "1.9.0"));
        assertFalse(VersionCompare.isOutdated("1.0.0", ""));
        assertFalse(VersionCompare.isOutdated("1.0.0", null));
        assertFalse(VersionCompare.isOutdated("1.0.0", "not-a-version"));
    }
}
