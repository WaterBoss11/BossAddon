package com.boss.pvp;

import autismclient.api.AutismAddon;
import autismclient.api.AutismAddons;

import autismclient.api.hud.HudElementProvider;
import autismclient.modules.Module;

import com.boss.pvp.module.automation.AutoPotModule;
import com.boss.pvp.module.automation.HitboxModule;
import com.boss.pvp.client.hud.FovCircleHud;
import com.boss.pvp.module.combat.AutoWeaponModule;
import com.boss.pvp.client.hud.AutoTotemHud;
import com.boss.pvp.client.hud.CombatHud;
import com.boss.pvp.module.movement.AntiKnockbackModule;
import com.boss.pvp.module.movement.NoSlowdownModule;
import com.boss.pvp.module.automation.AutoHookModule;
import com.boss.pvp.module.combat.BedAuraModule;
import com.boss.pvp.module.combat.HoleFillerModule;
import com.boss.pvp.module.movement.BurrowModule;
import com.boss.pvp.module.automation.AutoGapModule;
import com.boss.pvp.module.automation.InvManagerModule;
import com.boss.pvp.module.automation.AutoClutchModule;
import com.boss.pvp.module.automation.AutoLeaveModule;
import com.boss.pvp.module.automation.SelfDestructModule;
import com.boss.pvp.module.combat.AutoShootModule;
import com.boss.pvp.module.combat.AntiEntityPushModule;
import com.boss.pvp.module.combat.ReachModule;
import com.boss.pvp.module.combat.VelocityModule;
import com.boss.pvp.module.combat.CityMineModule;
import com.boss.pvp.module.combat.PistonPushModule;
import com.boss.pvp.module.combat.SpearAssistModule;
import com.boss.pvp.module.combat.ClickPearlModule;
import com.boss.pvp.module.combat.PearlChaserModule;
import com.boss.pvp.module.combat.AutoShieldModule;
import com.boss.pvp.module.combat.AntiFireballModule;
import com.boss.pvp.module.combat.BowReleaseModule;
import com.boss.pvp.module.combat.AutoMaceModule;
import com.boss.pvp.module.movement.AutoWindChargeModule;
import com.boss.pvp.module.movement.KeepSprintModule;
import com.boss.pvp.module.movement.HoleSnapModule;
import com.boss.pvp.module.movement.TargetStrafeModule;
import com.boss.pvp.module.movement.ReverseStepModule;
import com.boss.pvp.module.movement.ElytraPursuitModule;
import com.boss.pvp.module.movement.FastWebModule;
import com.boss.pvp.module.movement.FastLadderModule;
import com.boss.pvp.module.movement.WaterWalkModule;
import com.boss.pvp.module.movement.TridentBoostModule;

import net.minecraft.client.Minecraft;

public final class BossPvpAddon extends AutismAddon {
    // Module/HUD id namespace. MUST begin with the Fabric mod id ("bossaddon") — AUTISM 3.4 rejects any addon
    // module or HUD whose id does not start with "<modId>:" ("foreign namespace"). After the boss-pvp -> bossaddon
    // rename this namespace was left as the bare "boss-pvp", which made AUTISM reject every module (nothing showed
    // in the menu and the pvp/utility toggle had nothing real to toggle). It is now sub-namespaced under the mod
    // id: "bossaddon:pvp:<module>". The utility half uses "bossaddon:utility:" so the two halves stay distinct and
    // never collide (both ship a flagreport module). See ModuleNamespaceTest.
    public static final String ID = "bossaddon:pvp";

    public static AutoPotModule autoPot;
    public static HitboxModule hitbox;
    public static AutoWeaponModule autoWeapon;
    public static AntiKnockbackModule antiKnockback;
    public static AutoHookModule autoHook;
    public static BedAuraModule bedAura;
    public static HoleFillerModule holeFiller;
    public static BurrowModule burrow;
    public static AutoGapModule autoGap;
    public static InvManagerModule invManager;
    public static AutoClutchModule autoClutch;
    public static ReachModule reach;
    public static NoSlowdownModule noSlowdown;
    public static AutoLeaveModule autoLeave;
    public static AntiEntityPushModule antiEntityPush;
    public static SelfDestructModule selfDestruct;
    public static AutoShootModule autoShoot;
    public static VelocityModule velocity;
    public static AutoWindChargeModule autoWindCharge;
    public static KeepSprintModule keepSprint;
    public static HoleSnapModule holeSnap;
    public static TargetStrafeModule targetStrafe;
    public static ReverseStepModule reverseStep;
    public static ElytraPursuitModule elytraPursuit;
    public static FastWebModule fastWeb;
    public static FastLadderModule fastLadder;
    public static WaterWalkModule waterWalk;
    public static TridentBoostModule tridentBoost;
    public static SpearAssistModule spearAssist;
    public static PistonPushModule pistonPush;
    public static CityMineModule cityMine;
    public static ClickPearlModule clickPearl;
    public static PearlChaserModule pearlChaser;
    public static AutoShieldModule autoShield;
    public static AntiFireballModule antiFireball;
    public static BowReleaseModule bowRelease;
    public static AutoMaceModule autoMace;
    public static com.boss.pvp.module.misc.FlagReportModule flagReport;
    public static com.boss.pvp.module.misc.UpdateCheckModule updateCheck;
    public static com.boss.pvp.module.misc.VelocityClampModule velocityClamp;
    public static com.boss.pvp.module.misc.ReconfigureGuardModule reconfigureGuard;

    // Set by AutoCrystal while it is mid place->break cycle so KillAura defers and they don't fight
    // over the held hotbar slot (the "spaz"). Cleared by AutoCrystal on disable / when it stops acting.
    public static volatile boolean crystalActive = false;

    private static int autoTestCountdown = -1;
    private static boolean autoTestEnabled = false;

    // Every module registered below, kept so the flag reporter can snapshot enabled-state on a kick/crash.
    private static Module[] registeredModules;

    public static java.util.List<String> friends() {
        return com.boss.pvp.util.pvp.PvpUtil.friendNames();
    }

    /** "Name (Category)" for each currently-enabled boss-pvp module — the flag reporter's module snapshot. */
    public static java.util.List<String> enabledModuleSummary() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (registeredModules != null) {
            for (Module m : registeredModules) {
                if (m != null && m.isEnabled()) out.add(m.name() + " (Boss PVP)");
            }
        }
        return out;
    }

    public BossPvpAddon() {
        this.name = "Boss's PVP";
        this.authors = "@WaterBoss11";
    }

    @Override
    public int apiVersion() {
        return Math.min(autismclient.api.ApiVersion.CURRENT, AutismAddons.apiVersion());
    }

    @Override
    public void onInitialize() {
        autoPot = new AutoPotModule();
        hitbox = new HitboxModule();
        autoWeapon = new AutoWeaponModule();
        antiKnockback = new AntiKnockbackModule();
        autoHook = new AutoHookModule();
        bedAura = new BedAuraModule();
        holeFiller = new HoleFillerModule();
        burrow = new BurrowModule();
        autoGap = new AutoGapModule();
        invManager = new InvManagerModule();
        autoClutch = new AutoClutchModule();
        reach = new ReachModule();
        noSlowdown = new NoSlowdownModule();
        autoLeave = new AutoLeaveModule();
        antiEntityPush = new AntiEntityPushModule();
        selfDestruct = new SelfDestructModule();
        autoShoot = new AutoShootModule();
        velocity = new VelocityModule();
        autoWindCharge = new AutoWindChargeModule();
        keepSprint = new KeepSprintModule();
        holeSnap = new HoleSnapModule();
        targetStrafe = new TargetStrafeModule();
        reverseStep = new ReverseStepModule();
        elytraPursuit = new ElytraPursuitModule();
        fastWeb = new FastWebModule();
        fastLadder = new FastLadderModule();
        waterWalk = new WaterWalkModule();
        tridentBoost = new TridentBoostModule();
        spearAssist = new SpearAssistModule();
        pistonPush = new PistonPushModule();
        cityMine = new CityMineModule();
        clickPearl = new ClickPearlModule();
        pearlChaser = new PearlChaserModule();
        autoShield = new AutoShieldModule();
        antiFireball = new AntiFireballModule();
        bowRelease = new BowReleaseModule();
        autoMace = new AutoMaceModule();
        flagReport = new com.boss.pvp.module.misc.FlagReportModule();
        updateCheck = new com.boss.pvp.module.misc.UpdateCheckModule();
        velocityClamp = new com.boss.pvp.module.misc.VelocityClampModule();
        reconfigureGuard = new com.boss.pvp.module.misc.ReconfigureGuardModule();

        Module[] all = {
            autoPot,
            hitbox,
            antiKnockback,
            autoHook,
            bedAura,
            holeFiller,
            burrow,
            autoGap,
            invManager,
            autoClutch,
            reach,
            autoWeapon,
            noSlowdown,
            autoLeave,
            antiEntityPush,
            selfDestruct,
            autoShoot,
            velocity,
            autoWindCharge,
            keepSprint,
            holeSnap,
            targetStrafe,
            reverseStep,
            elytraPursuit,
            fastWeb,
            fastLadder,
            waterWalk,
            tridentBoost,
            spearAssist,
            pistonPush,
            cityMine,
            clickPearl,
            pearlChaser,
            autoShield,
            antiFireball,
            bowRelease,
            autoMace,
            flagReport,
            updateCheck,
            velocityClamp,
            reconfigureGuard
        };
        java.util.Arrays.sort(all, java.util.Comparator.comparing(Module::name, String.CASE_INSENSITIVE_ORDER));
        for (Module m : all) AutismAddons.modules().register(m);
        registeredModules = all;

        // BossAddon halves: register this half with the group toggle (?bossaddon pvp on|off) and apply a
        // persisted OFF state. The utility half registers itself from BossUtilityAddon.
        com.boss.pvp.util.AddonHalves.registerPvp(all);

        // Flag reporting (kick / packet-kick / crash -> boss-pvp-flags Discord). Reads its webhook from a
        // LOCAL client config (not a repo secret) and flushes any crash report persisted before a hard crash.
        com.boss.pvp.flag.FlagReporter.init();

        // Launch-time, read-only update check: one async GitHub Releases GET, notifies via chat + HUD if
        // this build is behind the latest release. Fire-and-forget; opt out via the "Update Checker" toggle.
        com.boss.pvp.update.UpdateChecker.init();

        // Chat relay (closed pilot). Inert unless relay.url + relay.invite are configured — a default install
        // never connects and shows no relay UI. See com.boss.pvp.relay.
        com.boss.pvp.relay.RelayManager.get().init();

        HudElementProvider[] huds = { new FovCircleHud(), new AutoTotemHud(), new CombatHud(), new com.boss.pvp.client.hud.UpdateHud() };
        for (HudElementProvider h : huds) {
            boolean ok = AutismAddons.hud().register(h);
            System.out.println("[BossPvP] HUD register '" + h.id() + "' (" + h.label() + ") -> " + ok);
        }

        logHudGateState();

        com.boss.pvp.util.pvp.PvpUtil.migrateFriends();
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, level, hand, target, hit) -> {
            Minecraft client = Minecraft.getInstance();
            if (player != client.player || !combatHooksActive(client)) return net.minecraft.world.InteractionResult.PASS;
            if (!canAttack(client, target)) return net.minecraft.world.InteractionResult.FAIL;
            if (autoWeapon != null && autoWeapon.isEnabled() && !player.isUsingItem()
                    && (autoMace == null || !autoMace.ownsAttack(target))
                    && target instanceof net.minecraft.world.entity.LivingEntity
                    && !autoWeapon.selectBestWeapon(target)) {
                return net.minecraft.world.InteractionResult.FAIL;
            }
            return net.minecraft.world.InteractionResult.PASS;
        });
        // The host's Packet attack mode skips Fabric's game-mode callback. True means CANCEL in this API.
        AutismAddons.events().onPacketSend(BossPvpAddon::cancelAttackPacket);
        AutismAddons.events().onPacketReceive(packet -> {
            if (!(packet instanceof net.minecraft.network.protocol.game.ClientboundEntityEventPacket event)
                    || event.getEventId() != 35) return;
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> com.boss.pvp.util.CombatManager.observePacket(client, packet));
        });
        AutismAddons.events().onGameLeft(BossPvpAddon::resetWorldState);

        // Auto-enable the test modules 3s after joining a localhost server (client-side, so they actually
        // turn on). Disable them again on disconnect so they don't linger after testing.
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            boolean local = isLocalhostServer(client);
            net.minecraft.client.multiplayer.ServerData sd = client.getCurrentServer();
            System.out.println("[BossPvP] JOIN: server='" + (sd == null ? "<null/singleplayer>" : sd.ip)
                + "' localhost=" + local);
            if (local) {
                autoTestCountdown = 60;
                System.out.println("[BossPvP] auto-enable armed (60-tick countdown; fires once mc.player+mc.level are in world)");
            }
        });
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (autoTestEnabled) disableTestModules();
        });

        AutismAddons.events().onTick(mc -> {
            if (mc.player == null || mc.level == null) return;

            com.boss.pvp.update.UpdateChecker.tick(mc);   // one-time "update available" chat notice, once in-world
            com.boss.pvp.relay.RelayManager.get().tick(mc);   // keep the relay's notion of your current server current

            if (autoTestCountdown > 0 && --autoTestCountdown == 0) enableTestModules();
            com.boss.pvp.command.BossAutoTestCommand.tickClient();

            // PVP half disabled -> genuinely inert: skip ALL pvp module ticking below (modules were also
            // force-disabled by AddonHalves, silencing their mixin-driven hooks). Infra above (update
            // check, relay, autotest plumbing) is NOT a module and keeps running.
            if (!com.boss.pvp.util.AddonHalves.pvpOn()) {
                com.boss.pvp.util.pvp.RotationManager.reset();
                com.boss.pvp.util.input.HeldSlotManager.reset();
                com.boss.pvp.util.CombatManager.reset();
                return;
            }
            com.boss.pvp.util.CombatManager.tick();
            if (autoPot.isEnabled())       autoPot.tick(mc);
            if (autoWeapon.isEnabled())    autoWeapon.tick(mc);
            if (antiKnockback.isEnabled()) antiKnockback.tick(mc);
            if (autoHook.isEnabled())      autoHook.tick(mc);
            if (bedAura.isEnabled())       bedAura.tick(mc);
            if (holeFiller.isEnabled())    holeFiller.tick(mc);
            if (burrow.isEnabled())        burrow.tick(mc);
            if (autoGap.isEnabled())       autoGap.tick(mc);
            if (invManager.isEnabled())    invManager.tick(mc);
            if (autoClutch.isEnabled())    autoClutch.tick(mc);
            if (reach.isEnabled())         reach.tick(mc);
            if (autoLeave.isEnabled())     autoLeave.tick(mc);
            if (selfDestruct.isEnabled())  selfDestruct.tick(mc);
            if (autoShoot.isEnabled())     autoShoot.tick(mc);
            if (velocity.isEnabled())      velocity.tick(mc);
            if (spearAssist.isEnabled())   spearAssist.tick(mc);
            if (pistonPush.isEnabled())    pistonPush.tick(mc);
            if (cityMine.isEnabled())      cityMine.tick(mc);
            if (clickPearl.isEnabled())    clickPearl.tick(mc);
            if (pearlChaser.isEnabled())   pearlChaser.tick(mc);
            if (antiFireball.isEnabled())  antiFireball.tick(mc);
            if (bowRelease.isEnabled())    bowRelease.tick(mc);
            if (autoShield.isEnabled())   autoShield.tick(mc);
            if (autoMace.isEnabled())      autoMace.tick(mc);
            tickMovement(mc);

            com.boss.pvp.util.pvp.RotationManager.endTickCommit();

            com.boss.pvp.util.input.OffhandManager.endTick();
            com.boss.pvp.util.input.HeldSlotManager.endTick();
        });
    }

    private static boolean combatHooksActive(Minecraft mc) {
        return mc != null && mc.player != null && mc.level != null
            && com.boss.pvp.util.AddonHalves.pvpOn() && !com.boss.pvp.util.pvp.PvpUtil.hostEnabled("hide");
    }

    private static boolean canAttack(Minecraft mc, net.minecraft.world.entity.Entity target) {
        boolean protectedTarget = combatHooksActive(mc) && target instanceof net.minecraft.world.entity.player.Player other
            && com.boss.pvp.util.pvp.PvpUtil.isProtectedPlayer(mc, other);
        boolean fireball = target instanceof net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
        if (combatHooksActive(mc) && autoMace != null && autoMace.isEnabled() && autoMace.blocksOtherAttack()) return false;
        return !com.boss.pvp.util.CombatManager.shouldCancelAttack(combatHooksActive(mc),
            com.boss.pvp.util.CombatManager.isCombatPaused(), protectedTarget, fireball);
    }

    private static boolean cancelAttackPacket(net.minecraft.network.protocol.Packet<?> packet) {
        if (!(packet instanceof net.minecraft.network.protocol.game.ServerboundAttackPacket attack)) return false;
        Minecraft mc = Minecraft.getInstance();
        if (!combatHooksActive(mc)) return false;
        var target = mc.level.getEntity(attack.entityId());
        if (target == null) return false;
        if (!canAttack(mc, target)) return true;
        if (autoWeapon != null && autoWeapon.isEnabled() && !mc.player.isUsingItem()
                && (autoMace == null || !autoMace.ownsAttack(target))
                && target instanceof net.minecraft.world.entity.LivingEntity) {
            if (!autoWeapon.consumePreparedAttack(target)
                    && (!autoWeapon.selectBestWeapon(target, true) || !autoWeapon.consumePreparedAttack(target))) return true;
        }
        com.boss.pvp.util.CombatManager.recordAttack(target);
        if (autoWeapon != null && autoWeapon.isEnabled()) autoWeapon.attackAccepted(target);
        if (autoMace != null && autoMace.isEnabled()) autoMace.attackAllowed(target);
        return false;
    }

    private static void tickMovement(Minecraft mc) {
        if (keepSprint.isEnabled()) keepSprint.tick(mc);
        if (autoWindCharge.isEnabled()) autoWindCharge.tick(mc);
        if (holeSnap.isEnabled()) holeSnap.tick(mc);
        boolean controlled = holeSnap.isEnabled() && holeSnap.controlsMovement();
        if (elytraPursuit.isEnabled()) {
            if (controlled) elytraPursuit.onDisable(); else elytraPursuit.tick(mc);
            controlled |= elytraPursuit.controlsMovement();
        }
        if (targetStrafe.isEnabled()) {
            if (controlled) targetStrafe.onDisable(); else targetStrafe.tick(mc);
            controlled |= targetStrafe.controlsMovement();
        }
        if (reverseStep.isEnabled()) {
            if (controlled) reverseStep.onDisable(); else reverseStep.tick(mc);
        }
    }

    private static java.nio.file.Path markerFile() {
        java.nio.file.Path cfgDir = net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
        return cfgDir.resolve("boss-pvp.properties");
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


    private static boolean isLocalhostServer(Minecraft client) {
        net.minecraft.client.multiplayer.ServerData sd = client.getCurrentServer();
        if (sd == null || sd.ip == null) return false;
        String host = sd.ip;
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        host = host.trim().toLowerCase(java.util.Locale.ROOT);
        return host.equals("localhost") || host.equals("127.0.0.1");
    }

    private static Module[] autoTestModules() {
        return new Module[]{ reach, noSlowdown, antiEntityPush };
    }

    private static void enableTestModules() {
        for (Module m : autoTestModules()) if (m != null && !m.isEnabled()) m.setEnabled(true);
        autoTestEnabled = true;
        autoTestCountdown = -1;
    }

    private static void disableTestModules() {
        for (Module m : autoTestModules()) if (m != null && m.isEnabled()) m.setEnabled(false);
        autoTestEnabled = false;
        autoTestCountdown = -1;
    }

    private static void resetWorldState() {
        if (registeredModules != null) {
            for (Module module : registeredModules) {
                if (module == null || !module.isEnabled()) continue;
                try {
                    module.onDisable();
                } catch (RuntimeException failure) {
                    System.err.println("[BossAddon] Cleanup failed for " + module.name() + ": " + failure.getClass().getSimpleName());
                }
            }
        }
        com.boss.pvp.util.CombatManager.reset();
        com.boss.pvp.util.input.HeldSlotManager.reset();
        com.boss.pvp.util.input.OffhandManager.reset();
        com.boss.pvp.util.pvp.RotationManager.reset();
        com.boss.pvp.util.pvp.PlayerSimulation.reset();
        com.boss.pvp.util.pvp.PvpUtil.resetCaches();
    }

    @Override
    public void onUnload() {
        resetWorldState();
    }

    @Override
    public String getPackage() {
        return "com.boss.pvp";
    }
}
