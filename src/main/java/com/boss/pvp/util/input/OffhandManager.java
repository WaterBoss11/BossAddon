package com.boss.pvp.util.input;

public final class OffhandManager {

    public static final int PRIORITY_AUTOTOTEM = 100;
    public static final int PRIORITY_OFFHAND   = 50;

    private static volatile Object pendingOwner = null;
    private static int pendingPriority = Integer.MIN_VALUE;
    private static volatile Object committedOwner = null;

    private OffhandManager() {}

    public static void request(Object owner, int priority) {
        if (owner == null) return;
        if (pendingOwner == null || priority > pendingPriority) {
            pendingOwner = owner;
            pendingPriority = priority;
        }
    }

    public static boolean mayWrite(Object owner) {
        return committedOwner != null && committedOwner == owner;
    }

    public static void endTick() {
        committedOwner = pendingOwner;
        pendingOwner = null;
        pendingPriority = Integer.MIN_VALUE;
    }

    public static void clear(Object owner) {
        if (committedOwner == owner) committedOwner = null;
        if (pendingOwner == owner) { pendingOwner = null; pendingPriority = Integer.MIN_VALUE; }
    }

    public static void reset() {
        pendingOwner = null;
        committedOwner = null;
        pendingPriority = Integer.MIN_VALUE;
    }
}
