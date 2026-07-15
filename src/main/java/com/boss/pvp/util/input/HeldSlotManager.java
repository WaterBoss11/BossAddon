package com.boss.pvp.util.input;

public final class HeldSlotManager {

    public static final int PRIORITY_AUTOPOT      = 100;
    public static final int PRIORITY_AUTOGAP      = 95;
    public static final int PRIORITY_BURROW       = 90;
    public static final int PRIORITY_AUTOCLUTCH   = 88;
    public static final int PRIORITY_AUTOHOOK     = 85;
    public static final int PRIORITY_SURROUND     = 80;
    public static final int PRIORITY_AUTOCRYSTAL  = 60;
    public static final int PRIORITY_AUTOANCHOR   = 55;
    public static final int PRIORITY_BEDAURA      = 50;
    public static final int PRIORITY_TRAPPER      = 45;
    public static final int PRIORITY_HOLEFILLER   = 40;
    public static final int PRIORITY_SHIELDBREAKER = 30;
    public static final int PRIORITY_SCAFFOLD     = 20;
    public static final int PRIORITY_AUTOXP       = 20;

    public static final int HEAL_PREEMPT_MIN = 95;

    private static final int RENEW_TIMEOUT_TICKS = 5;

    private static volatile Object owner = null;
    private static int ownerPriority = Integer.MIN_VALUE;
    private static Object pendingOwner = null;
    private static int pendingPriority = Integer.MIN_VALUE;
    private static boolean ownerRenewed = false;
    private static int ticksSinceRenew = 0;

    private HeldSlotManager() {}

    public static void request(Object module, int priority) {
        if (module == null) return;
        if (module == owner) {
            ownerRenewed = true;
            return;
        }
        if (pendingOwner == null || priority > pendingPriority) {
            pendingOwner = module;
            pendingPriority = priority;
        }
    }

    public static boolean holds(Object module) {
        return owner == module;
    }

    public static void release(Object module) {
        if (owner == module) {
            owner = null;
            ownerRenewed = false;
            ticksSinceRenew = 0;
        }
    }

    public static void endTick() {
        boolean gapForUnwind = false;
        if (owner != null) {
            if (ownerRenewed) ticksSinceRenew = 0;
            else if (++ticksSinceRenew > RENEW_TIMEOUT_TICKS) { owner = null; gapForUnwind = true; }
        }

        if (owner != null && pendingOwner != null && pendingPriority > ownerPriority
                && (pendingPriority >= HEAL_PREEMPT_MIN || ownerPriority <= PRIORITY_SCAFFOLD)) {
            owner = null;
            gapForUnwind = true;
        }

        if (owner == null && pendingOwner != null && !gapForUnwind) {
            owner = pendingOwner;
            ownerPriority = pendingPriority;
            ticksSinceRenew = 0;
        }
        ownerRenewed = false;
        pendingOwner = null;
        pendingPriority = Integer.MIN_VALUE;
    }

    public static void clear(Object module) {
        release(module);
        if (pendingOwner == module) { pendingOwner = null; pendingPriority = Integer.MIN_VALUE; }
    }

    public static void reset() {
        owner = null;
        ownerPriority = Integer.MIN_VALUE;
        pendingOwner = null;
        pendingPriority = Integer.MIN_VALUE;
        ownerRenewed = false;
        ticksSinceRenew = 0;
    }
}
