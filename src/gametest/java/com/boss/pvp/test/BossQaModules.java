package com.boss.pvp.test;

import autismclient.api.AutismAddons;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Actual addon registration, not constructor-only existence. No dependency on retired module classes. */
final class BossQaModules {
    static final List<String> NEW_MODULES = List.of(
            "autowindcharge", "keepsprint", "holesnap", "targetstrafe", "fastweb", "fastladder",
            "waterwalk", "reversestep", "tridentboost", "elytrapursuit", "clickpearl", "pearlchaser",
            "autoshield", "antifireball", "bowrelease", "spearassist", "pistonpush", "citymine", "automace");
    static final List<String> RETAINED = List.of("autoclutch", "autoweapon", "autohook", "autoshoot", "invmanager");
    // IDs only: assert that removed addon entries did not survive under a stale registration.
    private static final Set<String> RETIRED_IDS = Set.of(
            "autocrystal", "autoanchor", "killaura", "aimassist", "triggerbot", "autoarmor", "autototem",
            "surround", "scaffold", "sprint", "trapper", "trajectory", "criticals", "shieldbreaker",
            "fastplace", "nohurtcam", "timechanger", "weatherchanger", "offhand", "autoxp");
    private static Map<String, Module> registered = Map.of();

    private BossQaModules() { }

    static void checkRegistration(Minecraft client) {
        BossQaIsolation.profile();
        int expected = Integer.parseInt(BossQaIsolation.requiredProperty("boss.qa.expectedApi"));
        BossQaIsolation.require(AutismAddons.apiVersion() == expected, "Host API differs from the requested QA host");
        var pvp = AutismAddons.list().stream().filter(addon -> addon.getClass().getName().equals("com.boss.pvp.BossPvpAddon"))
                .findFirst().orElseThrow(() -> new AssertionError("Host did not accept the PvP addon entrypoint"));
        var utility = AutismAddons.list().stream().filter(addon -> addon.getClass().getName().equals("com.boss.utility.BossUtilityAddon"))
                .findFirst().orElseThrow(() -> new AssertionError("Host did not accept the utility addon entrypoint"));
        BossQaIsolation.require(pvp.apiVersion() == expected && utility.apiVersion() == expected, "Addon advertised the wrong API version");
        Map<String, Module> modules = snapshot(pvp.getClass());
        snapshot(utility.getClass()).forEach((id, module) ->
                BossQaIsolation.require(modules.putIfAbsent(id, module) == null, "Cross-half duplicate module ID: " + id));
        for (String suffix : NEW_MODULES) {
            Module module = require(modules, suffix);
            BossQaIsolation.require(!module.isEnabled(), "New module is enabled in a fresh profile: " + module.id());
            BossQaIsolation.require(!module.settings().isEmpty(), "New module has no actual settings: " + module.id());
            Set<String> settingIds = new java.util.HashSet<>();
            for (var setting : module.settings()) {
                BossQaIsolation.require(settingIds.add(setting.id()), "Duplicate setting ID: " + module.id() + "/" + setting.id());
                Object value = setting.get();
                BossQaIsolation.require(Objects.equals(value, setting.defaultValueTyped()),
                        "Fresh setting differs from its declared default: " + module.id() + "/" + setting.id());
                if (value instanceof Number number) {
                    BossQaIsolation.require(Double.isFinite(number.doubleValue()), "Non-finite new setting default: " + module.id() + "/" + setting.id());
                }
            }
        }
        for (String suffix : RETAINED) require(modules, suffix);
        for (Module module : modules.values()) {
            BossQaIsolation.require(module.id().startsWith("bossaddon:pvp:") || module.id().startsWith("bossaddon:utility:"),
                    "Foreign addon namespace: " + module.id());
            BossQaIsolation.require(module.isAddon(), "Host did not accept this as an addon module: " + module.id());
            String suffix = module.id().substring(module.id().lastIndexOf(':') + 1);
            BossQaIsolation.require(!RETIRED_IDS.contains(suffix), "Retired addon module still registered: " + module.id());
        }
        BossQaIsolation.require(modules.size() == 53, "Expected 41 PvP + 12 utility modules");
        BossQaIsolation.require(modules.keySet().stream().filter(id -> id.startsWith("bossaddon:utility:")).count() == 12,
                "Utility module count differs from the release manifest");
        Module cap = modules.get("bossaddon:utility:itemrendercap");
        BossQaIsolation.require(cap != null && !cap.isEnabled(), "Item Render Cap must register disabled");
        Map<String, Object> capDefaults = new LinkedHashMap<>();
        cap.settings().forEach(setting -> capDefaults.put(setting.id(), setting.defaultValueTyped()));
        BossQaIsolation.require(capDefaults.equals(Map.of("maxPerGroup", 5, "cellWidth", 4.0D)),
                "Item Render Cap setting IDs/defaults differ: " + capDefaults);
        registered = Map.copyOf(modules);
        Module clutch = require(modules, "autoclutch");
        try {
            BossQaIsolation.require(clutch == pvp.getClass().getField("autoClutch").get(null), "AutoClutch lost its retained module identity");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Retained AutoClutch field is absent", exception);
        }
        Map<String, Object> defaults = new LinkedHashMap<>();
        clutch.settings().forEach(setting -> defaults.put(setting.id(), setting.defaultValueTyped()));
        Map<String, Object> expectedClutch = Map.of("block", "Any full cube", "triggerDrop", 2,
                "airPlace", true, "delay", 40, "silent", true);
        BossQaIsolation.require(defaults.equals(expectedClutch), "AutoClutch setting IDs/defaults changed: " + defaults);
        BossQaIsolation.require(!clutch.isEnabled(), "AutoClutch must not start enabled in the isolated profile");
        BossQaIsolation.require(client.level == null, "Registration checks must run before opening the fixture world");
    }

    private static Map<String, Module> snapshot(Class<?> owner) {
        try {
            Field field = owner.getDeclaredField("registeredModules");
            field.setAccessible(true);
            Module[] actual = (Module[]) field.get(null);
            BossQaIsolation.require(actual != null && actual.length > 0, "Addon registration array absent: " + owner.getSimpleName());
            Map<String, Module> out = new LinkedHashMap<>();
            for (Module module : actual) {
                BossQaIsolation.require(module != null, "Null module in actual registration array");
                BossQaIsolation.require(out.putIfAbsent(module.id(), module) == null, "Duplicate module ID: " + module.id());
            }
            return out;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Cannot inspect the addon's actual registration array: " + owner.getSimpleName(), exception);
        }
    }

    static Module require(String suffix) {
        return require(registered, suffix);
    }

    private static Module require(Map<String, Module> modules, String suffix) {
        Module module = modules.get("bossaddon:pvp:" + suffix);
        BossQaIsolation.require(module != null, "Planned module is not registered: bossaddon:pvp:" + suffix);
        return module;
    }

    static void checkDisabledReporting() {
        BossQaIsolation.require(!staticBoolean("com.boss.pvp.flag.FlagConfig", "isConfigured")
                && !staticBoolean("com.boss.pvp.flag.FlagConfig", "isDualConfigured")
                && !staticBoolean("com.boss.utility.flag.FlagConfig", "isConfigured"), "QA reporting endpoints became configured");
        BossQaIsolation.require(!staticBoolean("com.boss.pvp.flag.FlagBridge", "isReportingEnabled")
                && !staticBoolean("com.boss.utility.flag.FlagBridge", "isReportingEnabled"), "A real QA reporting setting is on");
        BossQaIsolation.require(!staticBoolean("com.boss.pvp.relay.RelayConfig", "enabled")
                && !staticBoolean("com.boss.pvp.relay.RelayConfig", "isConfigured"), "BossChat is not disabled");
        for (String owner : List.of("com.boss.pvp.flag.FlagBridge", "com.boss.utility.flag.FlagBridge")) {
            BossQaIsolation.require(invoke(owner, "enabledModuleSummary", new Class<?>[0]) instanceof List<?>, "Flag bridge returned the wrong type");
        }
        invoke("com.boss.pvp.flag.LogRingBuffer", "install", new Class<?>[0]);
        String marker = "BOSS_QA_LOG_CAPTURE_MARKER_V1";
        LogManager.getLogger("BossQaTest").info(marker);
        Object captured = invoke("com.boss.pvp.flag.LogRingBuffer", "snapshot", new Class<?>[0]);
        BossQaIsolation.require(captured instanceof List<?> lines
                && lines.stream().anyMatch(line -> line instanceof String text && text.contains(marker)),
                "LogRingBuffer did not capture a real Log4j event");
        // Never turn reporting on, including transiently. Assertion failures must stay local.
    }

    static boolean slotHeld(Module module) {
        Object value = invoke("com.boss.pvp.util.input.HeldSlotManager", "holds", new Class<?>[]{Object.class}, module);
        BossQaIsolation.require(value instanceof Boolean, "HeldSlotManager.holds returned the wrong type");
        return (Boolean) value;
    }

    private static boolean staticBoolean(String owner, String method) {
        Object value = invoke(owner, method, new Class<?>[0]);
        BossQaIsolation.require(value instanceof Boolean, "Bridge boolean returned the wrong type: " + owner + "/" + method);
        return (Boolean) value;
    }

    private static Object invoke(String owner, String method, Class<?>[] parameterTypes, Object... arguments) {
        try {
            return Class.forName(owner).getMethod(method, parameterTypes).invoke(null, arguments);
        } catch (java.lang.reflect.InvocationTargetException exception) {
            throw new AssertionError("Real addon bridge failed: " + owner + "/" + method, exception.getCause());
        } catch (ReflectiveOperationException exception) {
            // Missing/renamed production APIs FAIL. No stubs, optional-success paths, or fallback values.
            throw new AssertionError("Required addon bridge is absent: " + owner + "/" + method, exception);
        }
    }

    static void checkSafeLifecycle(Minecraft client) {
        BossQaIsolation.require(client.level == null && client.player == null, "Offline lifecycle check requires a disconnected client");
        for (String suffix : NEW_MODULES) {
            Module module = require(suffix);
            try {
                for (int i = 0; i < 3; i++) {
                    module.setEnabled(true);
                    module.setEnabled(false);
                }
            } finally {
                module.setEnabled(false);
            }
            BossQaIsolation.require(!module.isEnabled(), "Offline lifecycle left module enabled: " + module.id());
        }
    }

    static void checkNewModulesDisabled() {
        for (String suffix : NEW_MODULES) {
            BossQaIsolation.require(!require(suffix).isEnabled(), "Unexpected enable across world join/cleanup: " + suffix);
        }
    }

    static List<String> registeredIds() {
        return registered.keySet().stream().sorted().toList();
    }
}
