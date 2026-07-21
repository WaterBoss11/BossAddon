package com.boss.pvp.util;

import com.boss.pvp.BossPvpAddon;

/**
 * Global Simple/Advanced menu mode for the module browser. In Simple mode (the default) each module shows only
 * its handful of everyday settings; Advanced reveals the technical tuning knobs. This is a pure display layer:
 * settings gated by {@code visibleWhen(MenuMode::advanced)} still function via config if already set — they are
 * just hidden from the menu until Advanced is on.
 *
 * <p>Deliberately NOT a module {@code Setting} — it's toggled by the {@code ?bossaddon menu} command and persisted
 * in {@code boss-pvp.properties}, so it adds no setting key to any module (keeps the setting-key inventory
 * unchanged). One flip applies across every module at once.
 */
public final class MenuMode {

    private static final String KEY = "advancedMenu";

    private static volatile boolean advanced;
    private static volatile boolean loaded;

    private MenuMode() {}

    /** True when Advanced settings should be shown. Lazily loads the persisted preference on first read. */
    public static boolean advanced() {
        if (!loaded) {
            try {
                advanced = Boolean.parseBoolean(BossPvpAddon.getConfigString(KEY, "false"));
            } catch (Throwable t) {
                advanced = false;   // fail to Simple — never let a config read break menu rendering
            }
            loaded = true;
        }
        return advanced;
    }

    /** Set the mode and persist it. */
    public static void setAdvanced(boolean value) {
        advanced = value;
        loaded = true;
        try {
            BossPvpAddon.setConfigString(KEY, Boolean.toString(value));
        } catch (Throwable t) {
            // in-memory change still applies this session even if the write fails
        }
    }

    /** Flip Simple &lt;-&gt; Advanced and return the new state. */
    public static boolean toggle() {
        setAdvanced(!advanced());
        return advanced;
    }
}
