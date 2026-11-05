package com.boss.utility.itemrendercap;

import java.util.function.ToIntFunction;

/** Frame-owned selection and lifecycle policy, kept free of Minecraft/host bootstrap for tests. */
final class ItemRenderFrame {
    private final ItemGroupSelector selector = new ItemGroupSelector();
    private Object world;
    private boolean active;

    /** The collector offers all items and returns the optional crosshair ID. Never called when inert. */
    void begin(Object world, boolean moduleEnabled, boolean utilityOn, boolean standalonePresent,
               int maxPerGroup, double cellWidth, ToIntFunction<ItemGroupSelector> collect) {
        if (world == null || !enabled(moduleEnabled, utilityOn, standalonePresent)) {
            if (this.world != null) reset();
            return;
        }
        if (this.world != world) reset();
        this.world = world;
        active = false;
        // Bound even values supplied by a hand-edited host configuration.
        int cap = Math.clamp(maxPerGroup, 0, 128);
        double width = Double.isFinite(cellWidth) ? Math.clamp(cellWidth, 0.5D, 128.0D) : 4.0D;
        selector.begin(cap, width);
        selector.finish(collect.applyAsInt(selector));
        active = true;
    }

    /** Queries never collect or change membership; disabling a gate takes effect immediately. */
    boolean allow(Object world, boolean moduleEnabled, boolean utilityOn, boolean standalonePresent, int id) {
        return !active || this.world != world || !enabled(moduleEnabled, utilityOn, standalonePresent)
                || selector.isVisible(id);
    }

    private static boolean enabled(boolean moduleEnabled, boolean utilityOn, boolean standalonePresent) {
        return moduleEnabled && utilityOn && !standalonePresent;
    }

    void reset() {
        selector.reset();
        world = null;
        active = false;
    }
}
