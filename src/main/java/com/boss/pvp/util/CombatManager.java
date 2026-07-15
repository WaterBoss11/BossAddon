package com.boss.pvp.util;

public final class CombatManager {

    private static int pauseTicks = 0;

    private CombatManager() {}

    public static void pauseCombat(int ticks) {
        if (ticks > pauseTicks) pauseTicks = ticks;
    }

    public static boolean isCombatPaused() {
        return pauseTicks > 0;
    }

    public static void tick() {
        if (pauseTicks > 0) pauseTicks--;
    }

    public static void reset() {
        pauseTicks = 0;
    }
}
