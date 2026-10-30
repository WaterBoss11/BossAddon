package com.boss.pvp.util.pvp;

import autismclient.modules.Module;

import java.util.Map;

/** Calls the installed host's actual AimAssist, without enabling it or overwriting its saved settings. */
public final class NativeAimBridge {
    private static Module aimAssist;
    private static final ThreadLocal<Map<String, String>> OPTIONS = new ThreadLocal<>();
    private static boolean borrowed;

    private NativeAimBridge() {}

    public static void capture(Module module) {
        if (module != null && "aim-assist".equals(module.id())) aimAssist = module;
    }

    public static boolean available() {
        return aimAssist != null;
    }

    public static String override(Module module, String key) {
        return module != null && module == aimAssist ? scopedValue(module.id(), key) : null;
    }

    public static String scopedValue(String id, String key) {
        Map<String, String> options = OPTIONS.get();
        return "aim-assist".equals(id) && options != null ? options.get(key) : null;
    }

    public static void withOptions(Map<String, String> options, Runnable action) {
        Map<String, String> previous = OPTIONS.get();
        try {
            OPTIONS.set(options);
            action.run();
        } finally {
            if (previous == null) OPTIONS.remove(); else OPTIONS.set(previous);
        }
    }

    public static boolean tick(Map<String, String> options) {
        Module module = aimAssist;
        if (module == null) return false;
        if (module.isEnabled()) {
            borrowed = false;
            return true;
        }
        borrowed = true;
        withOptions(options, module::tick);
        return true;
    }

    public static void reset() {
        OPTIONS.remove();
        if (borrowed && aimAssist != null && !aimAssist.isEnabled()) aimAssist.onDisable();
        borrowed = false;
    }
}
