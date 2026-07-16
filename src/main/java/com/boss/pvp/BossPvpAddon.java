package com.boss.pvp;

import autismclient.api.AutismAddon;
import autismclient.api.AutismAddons;

import autismclient.api.hud.HudElementProvider;
import autismclient.modules.Module;

import com.boss.pvp.module.automation.AutoPotModule;
import com.boss.pvp.module.automation.HitboxModule;
import com.boss.pvp.module.combat.AutoCrystalModule;
import com.boss.pvp.module.automation.AutoTotemModule;
import com.boss.pvp.module.combat.AimAssistModule;
import com.boss.pvp.client.hud.FovCircleHud;
import com.boss.pvp.module.combat.KillAuraModule;
import com.boss.pvp.module.combat.AutoWeaponModule;
import com.boss.pvp.client.hud.AutoTotemHud;
import com.boss.pvp.client.hud.CombatHud;
import com.boss.pvp.module.combat.SurroundModule;
import com.boss.pvp.module.combat.CriticalsModule;
import com.boss.pvp.module.automation.OffhandModule;
import com.boss.pvp.module.combat.ShieldBreakerModule;
import com.boss.pvp.module.automation.AutoArmorModule;
import com.boss.pvp.module.movement.AntiKnockbackModule;
import com.boss.pvp.module.movement.NoSlowdownModule;
import com.boss.pvp.module.automation.AutoHookModule;
import com.boss.pvp.module.movement.ScaffoldModule;
import com.boss.pvp.module.combat.AutoAnchorModule;
import com.boss.pvp.module.combat.BedAuraModule;
import com.boss.pvp.module.combat.HoleFillerModule;
import com.boss.pvp.module.automation.FastPlaceModule;
import com.boss.pvp.module.movement.BurrowModule;
import com.boss.pvp.module.automation.AutoGapModule;
import com.boss.pvp.module.automation.InvManagerModule;
import com.boss.pvp.module.combat.TrapperModule;
import com.boss.pvp.module.automation.AutoXPModule;
import com.boss.pvp.module.automation.AutoClutchModule;
import com.boss.pvp.module.automation.TriggerBotModule;
import com.boss.pvp.module.automation.AutoLeaveModule;
import com.boss.pvp.module.automation.SelfDestructModule;
import com.boss.pvp.module.render.NoHurtCamModule;
import com.boss.pvp.module.render.TrajectoryModule;
import com.boss.pvp.module.combat.AntiEntityPushModule;
import com.boss.pvp.module.combat.ReachModule;

import net.minecraft.client.Minecraft;

public final class BossPvpAddon extends AutismAddon {
    public static final String ID = "boss-pvp";

    public static AutoPotModule autoPot;
    public static HitboxModule hitbox;
    public static AutoCrystalModule autoCrystal;
    public static AutoTotemModule autoTotem;
    public static AimAssistModule aimAssist;
    public static KillAuraModule killAura;
    public static AutoWeaponModule autoWeapon;
    public static SurroundModule surround;
    public static CriticalsModule criticals;
    public static OffhandModule offhand;
    public static ShieldBreakerModule shieldBreaker;
    public static AutoArmorModule autoArmor;
    public static AntiKnockbackModule antiKnockback;
    public static AutoHookModule autoHook;
    public static ScaffoldModule scaffold;
    public static AutoAnchorModule autoAnchor;
    public static BedAuraModule bedAura;
    public static HoleFillerModule holeFiller;
    public static FastPlaceModule fastPlace;
    public static BurrowModule burrow;
    public static AutoGapModule autoGap;
    public static InvManagerModule invManager;
    public static TrapperModule trapper;
    public static AutoXPModule autoXP;
    public static AutoClutchModule autoClutch;
    public static TriggerBotModule triggerBot;
    public static ReachModule reach;
    public static NoSlowdownModule noSlowdown;
    public static AutoLeaveModule autoLeave;
    public static NoHurtCamModule noHurtCam;
    public static AntiEntityPushModule antiEntityPush;
    public static SelfDestructModule selfDestruct;
    public static TrajectoryModule trajectory;

    public static java.util.List<String> friends() {
        return killAura != null ? killAura.friends() : java.util.List.of();
    }

    @Override
    public int apiVersion() {
        return AutismAddons.apiVersion();
    }

    @Override
    public void onInitialize() {
        autoPot = new AutoPotModule();
        hitbox = new HitboxModule();
        autoCrystal = new AutoCrystalModule();
        autoTotem = new AutoTotemModule();
        aimAssist = new AimAssistModule();
        killAura = new KillAuraModule();
        autoWeapon = new AutoWeaponModule();
        surround = new SurroundModule();
        criticals = new CriticalsModule();
        offhand = new OffhandModule();
        shieldBreaker = new ShieldBreakerModule();
        autoArmor = new AutoArmorModule();
        antiKnockback = new AntiKnockbackModule();
        autoHook = new AutoHookModule();
        scaffold = new ScaffoldModule();
        autoAnchor = new AutoAnchorModule();
        bedAura = new BedAuraModule();
        holeFiller = new HoleFillerModule();
        fastPlace = new FastPlaceModule();
        burrow = new BurrowModule();
        autoGap = new AutoGapModule();
        invManager = new InvManagerModule();
        trapper = new TrapperModule();
        autoXP = new AutoXPModule();
        autoClutch = new AutoClutchModule();
        triggerBot = new TriggerBotModule();
        reach = new ReachModule();
        noSlowdown = new NoSlowdownModule();
        autoLeave = new AutoLeaveModule();
        noHurtCam = new NoHurtCamModule();
        antiEntityPush = new AntiEntityPushModule();
        selfDestruct = new SelfDestructModule();
        trajectory = new TrajectoryModule();

        Module[] all = {
            autoPot, hitbox, autoCrystal, autoTotem, aimAssist, killAura, surround, criticals,
            offhand, shieldBreaker, autoArmor, antiKnockback, autoHook, scaffold, autoAnchor,
            bedAura, holeFiller, fastPlace, burrow, autoGap, invManager, trapper, autoXP,
            autoClutch, triggerBot, reach, autoWeapon, noSlowdown, autoLeave, noHurtCam, antiEntityPush,
            selfDestruct, trajectory
        };
        for (Module m : all) AutismAddons.modules().register(m);

        HudElementProvider[] huds = { new FovCircleHud(), new AutoTotemHud(), new CombatHud() };
        for (HudElementProvider h : huds) {
            boolean ok = AutismAddons.hud().register(h);
            System.out.println("[BossPvP] HUD register '" + h.id() + "' (" + h.label() + ") -> " + ok);
        }

        logHudGateState();
        restoreHudVisibilityOnce();

        applyAlphabeticalMenuOrder(all);

        autismclient.api.AddonRegistrationResult cmdResult =
            AutismAddons.commands().registerDetailed(new com.boss.pvp.command.BossAutoTestCommand());
        boolean cmdFound = autismclient.commands.AutismCommands.find("bossautotest") != null;
        System.out.println("[BossPvP] command 'bossautotest' register: accepted=" + cmdResult.accepted()
            + " id=" + cmdResult.id() + " reason=" + cmdResult.reason()
            + " | find()=" + cmdFound + " | prefix='" + autismclient.commands.AutismCommands.effectivePrefix() + "'");

        AutismAddons.events().onTick(mc -> {
            if (mc.player == null || mc.level == null) return;

            com.boss.pvp.command.BossAutoTestCommand.tickClient();
            com.boss.pvp.util.CombatManager.tick();
            if (autoPot.isEnabled())       autoPot.tick(mc);
            if (autoCrystal.isEnabled())   autoCrystal.tick(mc);
            if (autoTotem.isEnabled())     autoTotem.tick(mc);
            if (aimAssist.isEnabled())     aimAssist.tick(mc);
            if (killAura.isEnabled())      killAura.tick(mc);
            if (autoWeapon.isEnabled())    autoWeapon.tick(mc);
            if (surround.isEnabled())      surround.tick(mc);
            if (criticals.isEnabled())     criticals.tick(mc);
            if (offhand.isEnabled())       offhand.tick(mc);
            if (shieldBreaker.isEnabled()) shieldBreaker.tick(mc);
            if (autoArmor.isEnabled())     autoArmor.tick(mc);
            if (antiKnockback.isEnabled()) antiKnockback.tick(mc);
            if (autoHook.isEnabled())      autoHook.tick(mc);
            if (scaffold.isEnabled())      scaffold.tick(mc);
            if (autoAnchor.isEnabled())    autoAnchor.tick(mc);
            if (bedAura.isEnabled())       bedAura.tick(mc);
            if (holeFiller.isEnabled())    holeFiller.tick(mc);
            if (burrow.isEnabled())        burrow.tick(mc);
            if (autoGap.isEnabled())       autoGap.tick(mc);
            if (invManager.isEnabled())    invManager.tick(mc);
            if (trapper.isEnabled())       trapper.tick(mc);
            if (autoXP.isEnabled())        autoXP.tick(mc);
            if (autoClutch.isEnabled())    autoClutch.tick(mc);
            if (triggerBot.isEnabled())    triggerBot.tick(mc);
            if (reach.isEnabled())         reach.tick(mc);
            if (autoLeave.isEnabled())     autoLeave.tick(mc);
            if (selfDestruct.isEnabled())  selfDestruct.tick(mc);

            com.boss.pvp.util.pvp.RotationManager.endTickCommit();

            com.boss.pvp.util.input.OffhandManager.endTick();
            com.boss.pvp.util.input.HeldSlotManager.endTick();
        });
    }

    private static void applyAlphabeticalMenuOrder(Module[] modules) {
        autismclient.util.AutismConfig cfg = autismclient.util.AutismConfig.getGlobal();
        if (cfg == null) {
            System.out.println("[BossPvP] menu order: AutismConfig.getGlobal() was null — skipped.");
            return;
        }
        if (cfg.moduleCategoryOrder == null) cfg.moduleCategoryOrder = new java.util.HashMap<>();

        java.util.Map<String, java.util.List<Module>> byCat = new java.util.LinkedHashMap<>();
        for (Module m : modules) {
            byCat.computeIfAbsent(m.category().name(), k -> new java.util.ArrayList<>()).add(m);
        }

        boolean migrated = getMarker(MIGRATION_MARKER_KEY);

        boolean changed = false;
        for (java.util.Map.Entry<String, java.util.List<Module>> e : byCat.entrySet()) {
            String catKey = e.getKey();
            java.util.List<Module> mods = e.getValue();
            mods.sort(java.util.Comparator.comparing(Module::name, String.CASE_INSENSITIVE_ORDER));

            java.util.List<String> alphabeticalIds = new java.util.ArrayList<>();
            java.util.Map<String, String> nameById = new java.util.HashMap<>();
            StringBuilder dbg = new StringBuilder();
            for (Module m : mods) {
                alphabeticalIds.add(m.id());
                nameById.put(m.id(), m.name());
                if (dbg.length() > 0) dbg.append(", ");
                dbg.append(m.name());
            }

            boolean overwrite;
            String reason;
            if (!migrated) {

                overwrite = true;
                reason = "v1 migration: forcing alphabetical";
            } else {

                java.util.List<String> saved = cfg.moduleCategoryOrder.get(catKey);
                if (saved == null || saved.isEmpty()) {
                    overwrite = true;
                    reason = "post-migration: no saved order";
                } else {
                    java.util.List<String> savedFiltered = new java.util.ArrayList<>();
                    for (String id : saved) if (nameById.containsKey(id)) savedFiltered.add(id);
                    java.util.List<String> savedAlpha = new java.util.ArrayList<>(savedFiltered);
                    savedAlpha.sort(java.util.Comparator.comparing(nameById::get, String.CASE_INSENSITIVE_ORDER));
                    overwrite = savedFiltered.equals(savedAlpha);
                    reason = overwrite
                        ? "post-migration: saved order already alphabetical (re-sorting to include new modules)"
                        : "post-migration: manual reorder detected — leaving user's order untouched (saved="
                            + saved + ")";
                }
            }

            if (overwrite) {
                cfg.moduleCategoryOrder.put(catKey, alphabeticalIds);
                changed = true;
                System.out.println("[BossPvP] menu order for category '" + catKey + "' (" + alphabeticalIds.size()
                    + " modules) -> alphabetical [" + reason + "]: " + dbg);
            } else {
                System.out.println("[BossPvP] menu order for category '" + catKey + "': " + reason);
            }
        }

        if (changed) persistModuleCategoryOrder(cfg.moduleCategoryOrder);

        if (!migrated) setMarker(MIGRATION_MARKER_KEY, "v1 alphabetical migration");
    }

    private static void persistModuleCategoryOrder(
            java.util.Map<String, java.util.List<String>> liveOrder) {
        try {
            autismclient.util.AutismConfig disk = autismclient.util.AutismConfig.load();
            disk.moduleCategoryOrder = liveOrder;
            disk.save();
            System.out.println("[BossPvP] menu order: persisted moduleCategoryOrder (non-clobbering disk overlay).");
        } catch (Exception ex) {
            System.out.println("[BossPvP] menu order: non-clobbering persist failed (" + ex
                + "); menu is correct in-memory this session, will retry next launch.");
        }
    }

    private static final String MIGRATION_MARKER_KEY = "bossPvpAlphabeticalMigratedV1";
    private static final String HUD_RESTORE_MARKER_KEY = "bossPvpHudVisibilityRestoredV1";

    private static java.nio.file.Path markerFile() {
        java.nio.file.Path cfgDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        return cfgDir.resolve("boss-pvp.properties");
    }

    private static boolean getMarker(String key) {
        try {
            java.nio.file.Path f = markerFile();
            if (!java.nio.file.Files.exists(f)) return false;
            java.util.Properties props = new java.util.Properties();
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(f)) {
                props.load(in);
            }
            return Boolean.parseBoolean(props.getProperty(key, "false"));
        } catch (Exception ex) {
            System.out.println("[BossPvP] marker '" + key + "': read failed (" + ex + ") — treating as unset.");
            return false;
        }
    }

    private static void setMarker(String key, String label) {
        try {
            java.nio.file.Path f = markerFile();
            java.nio.file.Files.createDirectories(f.getParent());
            java.util.Properties props = new java.util.Properties();
            if (java.nio.file.Files.exists(f)) {
                try (java.io.InputStream in = java.nio.file.Files.newInputStream(f)) {
                    props.load(in);
                }
            }
            props.setProperty(key, "true");
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(f)) {
                props.store(out, "boss-pvp one-time migration markers");
            }
            System.out.println("[BossPvP] marker '" + key + "' set (" + label + ") @ " + f);
        } catch (Exception ex) {

            System.out.println("[BossPvP] marker '" + key + "': write failed (" + ex + ") — will retry next launch.");
        }
    }

    public static String getConfigString(String key, String def) {
        try {
            java.nio.file.Path f = markerFile();
            if (!java.nio.file.Files.exists(f)) return def;
            java.util.Properties props = new java.util.Properties();
            try (java.io.InputStream in = java.nio.file.Files.newInputStream(f)) {
                props.load(in);
            }
            return props.getProperty(key, def);
        } catch (Exception ex) {
            System.out.println("[BossPvP] config '" + key + "': read failed (" + ex + ") — using default.");
            return def;
        }
    }

    public static void setConfigString(String key, String value) {
        try {
            java.nio.file.Path f = markerFile();
            java.nio.file.Files.createDirectories(f.getParent());
            java.util.Properties props = new java.util.Properties();
            if (java.nio.file.Files.exists(f)) {
                try (java.io.InputStream in = java.nio.file.Files.newInputStream(f)) {
                    props.load(in);
                }
            }
            props.setProperty(key, value);
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(f)) {
                props.store(out, "boss-pvp config");
            }
        } catch (Exception ex) {
            System.out.println("[BossPvP] config '" + key + "': write failed (" + ex + ").");
        }
    }

    private static void logHudGateState() {
        try {
            autismclient.util.AutismConfig cfg = autismclient.util.AutismConfig.getGlobal();
            String hud = moduleEnabledStr(cfg, "hud");
            String hide = moduleEnabledStr(cfg, "hide");
            System.out.println("[BossPvP] HUD gate at startup: module 'hud'.enabled=" + hud
                + ", module 'hide'.enabled=" + hide
                + " (HUD renders only when hud=true AND hide=false). Registered addon HUD ids: "
                + autismclient.api.hud.HudElements.ids());
        } catch (Throwable t) {
            System.out.println("[BossPvP] HUD gate: could not read state (" + t + ").");
        }
    }

    private static String moduleEnabledStr(autismclient.util.AutismConfig cfg, String id) {
        if (cfg == null || cfg.modules == null) return "?";
        autismclient.util.AutismConfig.ModuleState st = cfg.modules.get(id);
        return st == null ? "absent" : Boolean.toString(st.enabled);
    }

    private static void restoreHudVisibilityOnce() {
        if (getMarker(HUD_RESTORE_MARKER_KEY)) {
            System.out.println("[BossPvP] HUD visibility: restore already done (marker set) — leaving AUTISM's HUD settings untouched.");
            return;
        }
        try {
            Module hide = autismclient.modules.ModuleRegistry.get("hide");
            if (hide != null && hide.isEnabled()) {
                hide.setEnabled(false);
                System.out.println("[BossPvP] HUD visibility: cleared active 'hide' (panic) module so the HUD can render.");
            }
            Module hud = autismclient.modules.ModuleRegistry.get("hud");
            if (hud == null) {
                System.out.println("[BossPvP] HUD visibility: built-in 'hud' module not found — cannot restore (skipped).");
                return;
            }
            if (hud.isEnabled()) {
                System.out.println("[BossPvP] HUD visibility: 'hud' module already enabled — nothing to restore.");
            } else {
                hud.setEnabled(true);
                System.out.println("[BossPvP] HUD visibility: re-enabled the 'hud' module (one-time restore) — AUTISM + addon HUDs now render without holding right-shift.");
            }
            setMarker(HUD_RESTORE_MARKER_KEY, "v1 HUD visibility restore");
        } catch (Throwable t) {

            System.out.println("[BossPvP] HUD visibility: restore failed (" + t + ") — will retry next launch.");
        }
    }

    @Override
    public String getPackage() {
        return "com.boss.pvp";
    }
}
