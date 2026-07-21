package com.boss.pvp.util;

import autismclient.modules.Module;

import com.boss.pvp.BossPvpAddon;

/**
 * The two halves of BossAddon — PVP (com.boss.pvp modules) and UTILITY (the merged com.boss.utility
 * modules) — each toggleable as a group via {@code ?bossaddon pvp|utility on|off}.
 *
 * <p><b>Genuinely inert, not hidden:</b> turning a half OFF (1) disables every enabled module in that half —
 * so every mixin/render/packet hook that gates on {@code module.isEnabled()} short-circuits to vanilla — and
 * (2) each addon's per-tick dispatcher checks {@link #pvpOn()}/{@link #utilityOn()} and skips the half entirely,
 * so there is no tick overhead either. The set of modules that were enabled is snapshotted first and restored
 * exactly when the half is turned back ON, so no user state is lost.
 *
 * <p><b>Stays in sync with AUTISM's own module menu.</b> Each toggle goes through {@code setConfiguredEnabled}
 * (the exact call AUTISM's menu makes for a manual toggle) plus the module's {@code onEnable}/{@code onDisable}
 * hooks — see {@link #realView}. Plain {@code setEnabled} only flips the runtime flag and leaves AUTISM's
 * persisted "configured/offline" baseline stale, which let AUTISM reconcile the module back and drift the menu
 * out of sync with the command; this class fixes that.
 *
 * <p>Toggling operates on a {@link ModuleView} abstraction so the toggle/restore logic is unit-testable without
 * a running client (AUTISM's {@code Module} can't be instantiated headlessly). Settings are never touched —
 * only enabled flags — so every module keeps its exact configuration through any number of toggles.
 *
 * <p><b>Which modules belong to a half</b> is answered from AUTISM's <em>live</em> module registry, filtered by
 * the half's id-namespace ({@code bossaddon:pvp:} vs {@code bossaddon:utility:}) — see {@link #modulesFor}. This is the
 * authoritative source and does not depend on each addon's {@code registerPvp}/{@code registerUtility} call
 * having populated a cached array: the utility half was silently enumerating zero modules because it relied on
 * that array, and the live-registry filter fixes it. The registration arrays remain a fallback for headless
 * tests where the registry isn't reachable.
 */
public final class AddonHalves {

    private AddonHalves() {}

    public static final String PVP = "pvp";
    public static final String UTILITY = "utility";

    private static final String KEY_PREFIX = "halves.";          // halves.pvp / halves.utility = on|off
    private static final String SAVED_SUFFIX = ".saved";         // halves.<half>.saved = <id,id,...>

    /** Minimal view of a toggleable module — the real impl wraps an AUTISM {@link Module}; tests use fakes. */
    public interface ModuleView {
        String id();
        boolean isEnabled();
        /** Enable/disable in AUTISM's OWN state (menu + persistence), running the module's lifecycle hooks. */
        void setEnabled(boolean on);
    }

    // Registered by each addon after module registration. Volatile: read every tick, written once at init.
    private static volatile ModuleView[] pvpModules = new ModuleView[0];
    private static volatile ModuleView[] utilityModules = new ModuleView[0];

    // Cached flags so the per-tick checks are a field read, not a config lookup.
    private static volatile boolean pvpOn = true;
    private static volatile boolean utilityOn = true;

    // In-memory snapshot of what was enabled when a half was turned off — the primary source for restore
    // (also persisted to config below so it survives a restart). Keyed by half name.
    private static final java.util.Map<String, java.util.Set<String>> savedIds = new java.util.concurrent.ConcurrentHashMap<>();

    /** Supplies the live set of registered modules — the authoritative source a half is filtered from. */
    @FunctionalInterface
    public interface ModuleSource { java.util.List<ModuleView> all(); }

    // How each half's modules are enumerated. The truth is AUTISM's live module registry filtered by the half's
    // id-namespace ("bossaddon:pvp:" / "bossaddon:utility:"), NOT the array captured at registration — so a half is found
    // even if its addon's registerXxx() never ran (e.g. the second addon-in-one-mod init not populating it, which
    // silently zeroed the utility half). The registered arrays below remain a fallback for when the live registry
    // is unavailable (headless tests) or empty. Swapped in tests via setModuleSource.
    private static volatile ModuleSource moduleSource = AddonHalves::liveModulesFromRegistry;

    public static boolean pvpOn() { return pvpOn; }
    public static boolean utilityOn() { return utilityOn; }

    /** Called once by BossPvpAddon after registering its modules; applies a persisted OFF state. */
    public static void registerPvp(Module[] modules) { registerViews(PVP, wrap(modules)); }

    /** Called once by BossUtilityAddon after registering its modules; applies a persisted OFF state. */
    public static void registerUtility(Module[] modules) { registerViews(UTILITY, wrap(modules)); }

    /** Register a half's module views and apply any persisted OFF state. Package-private for tests. */
    static void registerViews(String half, ModuleView[] views) {
        ModuleView[] v = views == null ? new ModuleView[0] : views;
        if (PVP.equals(half)) {
            pvpModules = v;
            pvpOn = readFlag(PVP);
            if (!pvpOn) forceDisable(v);
        } else if (UTILITY.equals(half)) {
            utilityModules = v;
            utilityOn = readFlag(UTILITY);
            if (!utilityOn) forceDisable(v);
        }
    }

    /**
     * Toggle a half. OFF: snapshot the enabled set, then disable every module. ON: restore exactly the
     * snapshotted set. Returns a short human summary for command feedback, or null if the half name is unknown.
     */
    public static String setHalf(String half, boolean on) {
        if (!PVP.equals(half) && !UTILITY.equals(half)) return null;
        ModuleView[] mods = modulesFor(half);

        boolean was = PVP.equals(half) ? pvpOn : utilityOn;
        if (was == on) return half + " is already " + (on ? "on" : "off");

        int touched = 0;
        if (!on) {
            // Snapshot which modules are enabled BEFORE disabling, so ON restores the exact user state.
            java.util.Set<String> snap = new java.util.LinkedHashSet<>();
            for (ModuleView m : mods) {
                if (m != null && m.isEnabled()) { snap.add(m.id()); touched++; }
            }
            savedIds.put(half, snap);
            BossPvpAddon.setConfigString(KEY_PREFIX + half + SAVED_SUFFIX, String.join(",", snap));
            forceDisable(mods);
        } else {
            java.util.Set<String> ids = savedIds.remove(half);
            if (ids == null) ids = parseSaved(BossPvpAddon.getConfigString(KEY_PREFIX + half + SAVED_SUFFIX, ""));
            for (ModuleView m : mods) {
                if (m != null && ids.contains(m.id()) && applyEnabled(m, true)) touched++;
            }
            BossPvpAddon.setConfigString(KEY_PREFIX + half + SAVED_SUFFIX, "");
        }

        BossPvpAddon.setConfigString(KEY_PREFIX + half, on ? "on" : "off");
        if (PVP.equals(half)) pvpOn = on; else utilityOn = on;
        return half + " half " + (on ? "ON — restored " + touched + " module(s)"
                                     : "OFF — disabled " + touched + " enabled module(s)");
    }

    /** Parse a comma-separated saved-id list. Pure/testable. */
    static java.util.Set<String> parseSaved(String s) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (s != null && !s.isBlank()) out.addAll(java.util.Arrays.asList(s.split(",")));
        return out;
    }

    /** One-line status for the command. */
    public static String status() {
        return "pvp: " + (pvpOn ? "on" : "off") + " (" + modulesFor(PVP).length + " modules) | "
             + "utility: " + (utilityOn ? "on" : "off") + " (" + modulesFor(UTILITY).length + " modules)";
    }

    /** The id-namespace a half's modules carry — how they are told apart in the live registry. */
    private static String idPrefix(String half) {
        if (PVP.equals(half)) return BossPvpAddon.ID + ":";                      // "bossaddon:pvp:"
        if (UTILITY.equals(half)) return com.boss.utility.BossUtilityAddon.ID + ":";   // "bossaddon:utility:"
        return null;
    }

    /**
     * The modules that belong to a half. Preferred source: the live module registry filtered by the half's
     * id-namespace — always accurate and independent of whether the half's addon populated its registration array.
     * Falls back to the array captured at registration when the live registry yields nothing (headless tests, or a
     * registry API that isn't reachable).
     */
    static ModuleView[] modulesFor(String half) {
        String prefix = idPrefix(half);
        if (prefix != null) {
            java.util.List<ModuleView> matched = new java.util.ArrayList<>();
            for (ModuleView m : moduleSource.all()) {
                String id = m == null ? null : safeId(m);
                if (id != null && id.startsWith(prefix)) matched.add(m);
            }
            if (!matched.isEmpty()) return matched.toArray(new ModuleView[0]);
        }
        return PVP.equals(half) ? pvpModules : UTILITY.equals(half) ? utilityModules : new ModuleView[0];
    }

    /** The ids of a half's modules (for status/diagnostics and tests). */
    static java.util.List<String> moduleIds(String half) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (ModuleView m : modulesFor(half)) { String id = m == null ? null : safeId(m); if (id != null) ids.add(id); }
        return ids;
    }

    private static String safeId(ModuleView m) {
        try { return m.id(); } catch (Throwable ignored) { return null; }
    }

    /** Wrap every live AUTISM module as a {@link ModuleView}. Guarded so a headless/test context (no registry)
     * falls back to the registered arrays rather than throwing. */
    private static java.util.List<ModuleView> liveModulesFromRegistry() {
        try {
            java.util.List<ModuleView> out = new java.util.ArrayList<>();
            for (Module m : autismclient.modules.ModuleRegistry.all()) if (m != null) out.add(realView(m));
            return out;
        } catch (Throwable t) {
            return java.util.List.of();
        }
    }

    /** Override the live module source (package-private, for tests). Null restores the default AUTISM registry. */
    static void setModuleSource(ModuleSource src) {
        moduleSource = src != null ? src : AddonHalves::liveModulesFromRegistry;
    }

    private static boolean readFlag(String half) {
        return !"off".equalsIgnoreCase(BossPvpAddon.getConfigString(KEY_PREFIX + half, "on"));
    }

    private static void forceDisable(ModuleView[] mods) {
        for (ModuleView m : mods) applyEnabled(m, false);
    }

    /** Apply a single module's enabled state (only when it actually changes). Returns true if it changed. */
    private static boolean applyEnabled(ModuleView m, boolean on) {
        if (m == null || m.isEnabled() == on) return false;
        m.setEnabled(on);
        return true;
    }

    private static ModuleView[] wrap(Module[] mods) {
        int n = mods == null ? 0 : mods.length;
        ModuleView[] v = new ModuleView[n];
        for (int i = 0; i < n; i++) v[i] = realView(mods[i]);
        return v;
    }

    /**
     * The production {@link ModuleView} — wraps an AUTISM {@link Module}. {@code setEnabled} uses
     * {@code setConfiguredEnabled} (what the menu itself calls: runtime flag + AUTISM's configured/offline
     * baseline + save + menu refresh) and then fires the module's {@code onEnable}/{@code onDisable} hooks,
     * which {@code setConfiguredEnabled} skips — keeping runtime behaviour identical to the old {@code setEnabled}
     * path while finally syncing AUTISM's own menu/state.
     */
    private static ModuleView realView(Module m) {
        return new ModuleView() {
            @Override public String id() { return m == null ? null : m.id(); }
            @Override public boolean isEnabled() { return m != null && m.isEnabled(); }
            @Override public void setEnabled(boolean on) {
                if (m == null) return;
                try {
                    m.setConfiguredEnabled(on);
                    if (on) m.onEnable(); else m.onDisable();
                } catch (Throwable ignored) {
                    // a toggle must never break the command
                }
            }
        };
    }
}
