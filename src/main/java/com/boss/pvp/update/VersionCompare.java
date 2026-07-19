package com.boss.pvp.update;

/**
 * Pure semantic-version comparison for the launch-time update check. No Minecraft/Fabric deps, so it can be
 * unit-tested directly. Tolerant of a leading {@code v}, differing segment counts, and trailing pre-release
 * suffixes (e.g. {@code 1.2.0-rc1}); anything it can't read as a version number is treated as "not comparable"
 * so the check never nags on a blank or garbage response.
 */
public final class VersionCompare {

    private VersionCompare() {}

    /** Strip a leading {@code v}/{@code V} and surrounding whitespace. Null-safe -&gt; {@code ""}. */
    public static String normalize(String v) {
        if (v == null) return "";
        String s = v.trim();
        if (!s.isEmpty() && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) s = s.substring(1);
        return s.trim();
    }

    /**
     * Compare two version strings numerically, segment by segment. Returns {@code <0} if {@code a} is older
     * than {@code b}, {@code 0} if equal, {@code >0} if newer. Missing segments count as 0 (so {@code 1.2}
     * equals {@code 1.2.0}). Each segment compares by its leading integer ({@code 10-rc1} -&gt; 10); a segment
     * with no leading digits is 0.
     */
    public static int compare(String a, String b) {
        String[] as = normalize(a).split("\\.");
        String[] bs = normalize(b).split("\\.");
        int n = Math.max(as.length, bs.length);
        for (int i = 0; i < n; i++) {
            int av = i < as.length ? leadingInt(as[i]) : 0;
            int bv = i < bs.length ? leadingInt(bs[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    /**
     * True when {@code current} is strictly older than {@code latest}. Returns false if either side has no
     * usable version number, so an empty/garbage GitHub response or an unknown local version can never trigger
     * a false "out of date" notice.
     */
    public static boolean isOutdated(String current, String latest) {
        if (!hasNumber(current) || !hasNumber(latest)) return false;
        return compare(current, latest) < 0;
    }

    private static int leadingInt(String seg) {
        int i = 0;
        while (i < seg.length() && Character.isDigit(seg.charAt(i))) i++;
        if (i == 0) return 0;
        try {
            return Integer.parseInt(seg.substring(0, i));
        } catch (NumberFormatException e) {
            return 0;   // absurdly long segment: treat as unknown rather than throw
        }
    }

    private static boolean hasNumber(String v) {
        String s = normalize(v);
        for (int i = 0; i < s.length(); i++) {
            if (Character.isDigit(s.charAt(i))) return true;
        }
        return false;
    }
}
