package com.boss.pvp.util;

import autismclient.modules.Module;

import com.boss.pvp.BossPvpAddon;

/**
 * The two halves of BossAddon — PVP (com.boss.pvp modules) and UTILITY (the merged com.boss.utility
 * modules) — each toggleable as a group via {@code ?bossaddon pvp|utility on|off}.
 *
 * <p><b>Genuinely inert, not hidden:</b> turning a half OFF (1) force-disables every enabled module in
 * that half — so every mixin/render/packet hook that gates on {@code module.isEnabled()} short-circuits
 * to vanilla — and (2) each addon's per-tick dispatcher checks {@link #pvpOn()}/{@link #utilityOn()} and
 * skips the half entirely, so there is no tick overhead either. The set of modules that were enabled is
 * snapshotted to config first, and restored exactly when the half is turned back ON, so no user state is
 * lost. The on/off flags and the snapshot persist across restarts (boss-pvp.properties).
 *
 * <p>Settings themselves are never touched — only the enabled flags — so every module keeps its exact
 * configuration through any number of toggles.
 */
public final class AddonHalves {

    private AddonHalves() {}

    public static final String PVP = "pvp";
    public static final String UTILITY = "utility";

    private static final String KEY_PREFIX = "halves.";          // halves.pvp / halves.utility = on|off
    private static final String SAVED_SUFFIX = ".saved";         // halves.<half>.saved = <id,id,...>

    // Registered by each addon after module registration. Volatile: read every tick, written once at init.
    private static volatile Module[] pvpModules = new Module[0];
    private static volatile Module[] utilityModules = new Module[0];

    // Cached flags so the per-tick checks are a field read, not a config lookup.
    private static volatile boolean pvpOn = true;
    private static volatile boolean utilityOn = true;

    public static boolean pvpOn() { return pvpOn; }
    public static boolean utilityOn() { return utilityOn; }

    /** Called once by BossPvpAddon after registering its modules; applies a persisted OFF state. */
    public static void registerPvp(Module[] modules) {
        pvpModules = modules == null ? new Module[0] : modules;
        pvpOn = readFlag(PVP);
        if (!pvpOn) forceDisable(pvpModules);
    }

    /** Called once by BossUtilityAddon after registering its modules; applies a persisted OFF state. */
    public static void registerUtility(Module[] modules) {
        utilityModules = modules == null ? new Module[0] : modules;
        utilityOn = readFlag(UTILITY);
        if (!utilityOn) forceDisable(utilityModules);
    }

    /**
     * Toggle a half. OFF: snapshot the enabled set to config, then disable every module. ON: restore
     * exactly the snapshotted set. Returns a short human summary for command feedback, or null if the
     * half name is unknown.
     */
    public static String setHalf(String half, boolean on) {
        Module[] mods;
        if (PVP.equals(half)) mods = pvpModules;
        else if (UTILITY.equals(half)) mods = utilityModules;
        else return null;

        boolean was = PVP.equals(half) ? pvpOn : utilityOn;
        if (was == on) return half + " is already " + (on ? "on" : "off");

        int touched = 0;
        if (!on) {
            // Snapshot which modules are enabled BEFORE disabling, so ON restores the exact user state.
            StringBuilder saved = new StringBuilder();
            for (Module m : mods) {
                if (m != null && m.isEnabled()) {
                    if (saved.length() > 0) saved.append(',');
                    saved.append(m.id());
                    touched++;
                }
            }
            BossPvpAddon.setConfigString(KEY_PREFIX + half + SAVED_SUFFIX, saved.toString());
            forceDisable(mods);
        } else {
            String saved = BossPvpAddon.getConfigString(KEY_PREFIX + half + SAVED_SUFFIX, "");
            java.util.Set<String> ids = new java.util.HashSet<>();
            if (saved != null && !saved.isBlank()) ids.addAll(java.util.Arrays.asList(saved.split(",")));
            for (Module m : mods) {
                if (m != null && ids.contains(m.id()) && !m.isEnabled()) {
                    try { m.setEnabled(true); touched++; } catch (Throwable ignored) { }
                }
            }
            BossPvpAddon.setConfigString(KEY_PREFIX + half + SAVED_SUFFIX, "");
        }

        BossPvpAddon.setConfigString(KEY_PREFIX + half, on ? "on" : "off");
        if (PVP.equals(half)) pvpOn = on; else utilityOn = on;
        return half + " half " + (on ? "ON — restored " + touched + " module(s)"
                                     : "OFF — disabled " + touched + " enabled module(s)");
    }

    /** One-line status for the command. */
    public static String status() {
        return "pvp: " + (pvpOn ? "on" : "off") + " (" + pvpModules.length + " modules) | "
             + "utility: " + (utilityOn ? "on" : "off") + " (" + utilityModules.length + " modules)";
    }

    private static boolean readFlag(String half) {
        return !"off".equalsIgnoreCase(BossPvpAddon.getConfigString(KEY_PREFIX + half, "on"));
    }

    private static void forceDisable(Module[] mods) {
        for (Module m : mods) {
            if (m != null && m.isEnabled()) {
                try { m.setEnabled(false); } catch (Throwable ignored) { }
            }
        }
    }
}
