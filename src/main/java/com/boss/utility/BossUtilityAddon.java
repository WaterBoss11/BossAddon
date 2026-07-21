package com.boss.utility;

import autismclient.api.AutismAddon;
import autismclient.api.AutismAddons;
import autismclient.api.hud.HudElementProvider;
import autismclient.modules.Module;

import com.boss.utility.hud.ArmorHud;
import com.boss.utility.hud.CompassHud;
import com.boss.utility.hud.ItemHud;
import com.boss.utility.hud.PotionTimersHud;
import com.boss.utility.module.misc.NotifierModule;
import com.boss.utility.module.movement.SprintModule;
import com.boss.utility.module.movement.StepModule;
import com.boss.utility.module.player.AntiAfkModule;
import com.boss.utility.module.player.AutoRespawnModule;
import com.boss.utility.module.player.ChestSwapModule;
import com.boss.utility.module.render.AmbienceModule;
import com.boss.utility.module.render.TimeChangerModule;
import com.boss.utility.module.render.WeatherChangerModule;
import com.boss.utility.module.world.AutoBreedModule;
import com.boss.utility.module.world.AutoMountModule;
import com.boss.utility.module.world.AutoShearerModule;

import net.minecraft.client.Minecraft;

/**
 * AUTISM Client addon entry point for BossUtility — general utility / movement / QoL modules.
 *
 * <p>Module logic in this addon is studied and rewritten from Meteor Client
 * (https://github.com/MeteorDevelopment/meteor-client, GPL-3.0). BossUtility is likewise licensed
 * GPL-3.0. See the README for the credit and license notice.
 */
public final class BossUtilityAddon extends AutismAddon {

    // Module id namespace. MUST begin with the Fabric mod id ("bossaddon") or AUTISM 3.4 rejects every module as
    // "foreign namespace". Sub-namespaced under the mod id and distinct from the pvp half ("bossaddon:pvp:") so the
    // two halves never collide. See BossPvpAddon#ID and ModuleNamespaceTest.
    public static final String ID = "bossaddon:utility";

    // Movement
    public static SprintModule sprint;
    public static StepModule step;
    // Player
    public static AntiAfkModule antiAfk;
    public static AutoRespawnModule autoRespawn;
    public static ChestSwapModule chestSwap;
    // Misc
    public static NotifierModule notifier;
    // Render
    public static TimeChangerModule timeChanger;
    public static WeatherChangerModule weatherChanger;
    public static AmbienceModule ambience;
    // World
    public static AutoMountModule autoMount;
    public static AutoBreedModule autoBreed;
    public static AutoShearerModule autoShearer;
    // Misc — passive report toggle (registered, but does no per-tick work)
    public static com.boss.utility.module.misc.FlagReportModule flagReport;

    private static Module[] tickables;
    private static Module[] registeredModules;

    public BossUtilityAddon() {
        this.name = "BossUtility";
        this.authors = "@WaterBoss11";
    }

    @Override
    public int apiVersion() {
        return AutismAddons.apiVersion();
    }

    @Override
    public void onInitialize() {
        sprint = new SprintModule();
        step = new StepModule();
        antiAfk = new AntiAfkModule();
        autoRespawn = new AutoRespawnModule();
        chestSwap = new ChestSwapModule();
        notifier = new NotifierModule();
        timeChanger = new TimeChangerModule();
        weatherChanger = new WeatherChangerModule();
        ambience = new AmbienceModule();
        autoMount = new AutoMountModule();
        autoBreed = new AutoBreedModule();
        autoShearer = new AutoShearerModule();
        flagReport = new com.boss.utility.module.misc.FlagReportModule();

        Module[] all = {
            sprint, step, antiAfk, autoRespawn, chestSwap, notifier,
            timeChanger, weatherChanger, ambience, autoMount, autoBreed, autoShearer, flagReport
        };
        for (Module m : all) AutismAddons.modules().register(m);
        registeredModules = all;
        // ChestSwap is a one-shot; everything else is driven per-tick below.
        tickables = all;

        // BossAddon halves: register this half with the group toggle (?bossaddon utility on|off) and apply
        // a persisted OFF state. See com.boss.pvp.util.AddonHalves.
        com.boss.pvp.util.AddonHalves.registerUtility(all);

        HudElementProvider[] huds = { new ItemHud(), new ArmorHud(), new PotionTimersHud(), new CompassHud() };
        for (HudElementProvider h : huds) AutismAddons.hud().register(h);

        // Flag reporting (kick / packet-kick / crash -> BossUtility flags Discord). Baked webhook; users opt
        // out via the "Crash & Kick Reports" toggle. Flushes any crash report persisted before a hard crash.
        com.boss.utility.flag.FlagReporter.init();

        AutismAddons.events().onTick(BossUtilityAddon::onClientTick);
    }

    /** "Name (Category)" for each currently-enabled BossUtility module — the flag reporter's module snapshot. */
    public static java.util.List<String> enabledModuleSummary() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (registeredModules != null) {
            for (Module m : registeredModules) {
                if (m != null && m.isEnabled()) out.add(m.name() + " (" + m.category().name() + ")");
            }
        }
        return out;
    }

    private static void onClientTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        // Utility half disabled -> genuinely inert: skip ALL utility module ticking (modules were also
        // force-disabled by AddonHalves, which silences their mixin/render/packet hooks too).
        if (!com.boss.pvp.util.AddonHalves.utilityOn()) return;
        if (sprint.isEnabled()) sprint.tick(mc);
        if (step.isEnabled()) step.tick(mc);
        if (antiAfk.isEnabled()) antiAfk.tick(mc);
        if (autoRespawn.isEnabled()) autoRespawn.tick(mc);
        if (notifier.isEnabled()) notifier.tick(mc);
        if (timeChanger.isEnabled()) timeChanger.tick(mc);
        if (weatherChanger.isEnabled()) weatherChanger.tick(mc);
        if (autoMount.isEnabled()) autoMount.tick(mc);
        if (autoBreed.isEnabled()) autoBreed.tick(mc);
        if (autoShearer.isEnabled()) autoShearer.tick(mc);
    }

    @Override
    public String getPackage() {
        return "com.boss.utility";
    }
}
