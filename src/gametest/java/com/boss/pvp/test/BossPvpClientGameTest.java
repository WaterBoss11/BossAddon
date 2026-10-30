package com.boss.pvp.test;

import autismclient.modules.Module;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Real-client QA batches, compiled only in src/gametest. The isolated launcher is the launch contract.
 *
 * <p>The native AUTISM preference customMainMenu=false is seeded in this test's NEW profile only.
 * API4's independent Early Access gate remains a genuine blocker and is never bypassed.
 * Fabric's original server-disconnected AND vanilla-TitleScreen final
 * assertions stay intact: no framework redirect, catch-and-pass, or production menu change is used.
 * A result marker means only that these assertions ran; process exit zero is additionally required.
 */
@SuppressWarnings("UnstableApiUsage")
public final class BossPvpClientGameTest implements FabricClientGameTest {
    private final JsonObject result = new JsonObject();
    private final JsonArray cases = new JsonArray();

    @Override
    public void runTest(ClientGameTestContext context) {
        BossQaIsolation.profile();
        String batch = System.getProperty("boss.qa.batch", "registration");
        BossQaIsolation.require(batch.equals("registration") || batch.equals("fixtures") || batch.equals("autoclutch") || batch.equals("damage-controls") || batch.equals("deferred-attacks") || batch.equals("automace-native") || batch.equals("automace-support-native"),
                "Unknown QA batch (no silent fallback): " + batch);
        result.addProperty("schema", "boss-qa-v1");
        result.addProperty("runId", BossQaIsolation.profile().getFileName().toString());
        result.addProperty("pid", ProcessHandle.current().pid());
        result.addProperty("batch", batch);
        result.addProperty("api", Integer.parseInt(BossQaIsolation.requiredProperty("boss.qa.expectedApi")));
        result.addProperty("hostVersion", FabricLoader.getInstance().getModContainer("autism").orElseThrow()
                .getMetadata().getVersion().getFriendlyString());
        result.add("cases", cases);
        try {
            if (batch.equals("automace-native") || batch.equals("automace-support-native")) {
                BossQaIsolation.require("3".equals(System.getProperty("boss.qa.expectedApi")), "AutoMace native batch is API3 only");
                boolean supportOnly = batch.equals("automace-support-native");
                result.addProperty("scope", supportOnly ? "remaining three AutoMace support features only; no previous ten or timing controls rerun"
                        : "separate AutoMace native aim/gravity/Lunge; stop first failure; no deferred controls rerun");
                try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(true).create()) {
                    world.getClientLevel().waitForChunksDownload();
                    try (BossQaNativeFixtures fixtures = new BossQaNativeFixtures(context)) {
                        fixtures.buildArena();
                        java.util.UUID targetId = fixtures.createPassiveTarget();
                        try (BossQaAutoMaceNative20261018 autoMace = new BossQaAutoMaceNative20261018(context, fixtures, targetId)) {
                            if (supportOnly) runCase("remaining-three-AutoMace-support-native-fail-fast", autoMace::runSupportBatch);
                            else runCase("focused-AutoMace-native-20261018-fail-fast", autoMace::runAll);
                        }
                    }
                }
            } else if (batch.equals("deferred-attacks")) {
                BossQaIsolation.require("3".equals(System.getProperty("boss.qa.expectedApi")), "Deferred native batch is API3 only");
                result.addProperty("scope", "new deferred AutoWeapon implementation only; no previous control matrix rerun");
                try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(true).create()) {
                    world.getClientLevel().waitForChunksDownload();
                    try (BossQaNativeFixtures fixtures = new BossQaNativeFixtures(context)) {
                        fixtures.buildArena();
                        java.util.UUID targetId = fixtures.createPassiveTarget();
                        try (BossQaDeferredAttacks deferred = new BossQaDeferredAttacks(context, fixtures, targetId)) {
                            runCase("focused-deferred-native-attacks-fail-fast", deferred::runAll);
                        }
                    }
                }
            } else {
                runCase("registered-identities-18-new-and-5-retained", () -> context.runOnClient(BossQaModules::checkRegistration));
                runCase("reporting-and-bosschat-disabled-log-capture", () -> context.runOnClient(client -> BossQaModules.checkDisabledReporting()));
                runCase("offline-enable-disable-cleanup-only", () -> context.runOnClient(BossQaModules::checkSafeLifecycle));
                if (!batch.equals("registration")) runWorldBatch(context, batch);
            }
            runCase("real-disconnected-vanilla-title-state", () -> {
                context.setScreen(TitleScreen::new);
                context.waitTicks(3);
                context.runOnClient(client -> {
                    String screen = client.gui.screen() == null ? "null" : client.gui.screen().getClass().getName();
                    boolean customMenu = autismclient.util.AutismConfig.getGlobal().customMainMenu;
                    result.addProperty("observedFinalScreen", screen);
                    result.addProperty("observedFinalLevelPresent", client.level != null);
                    result.addProperty("observedFinalServerPresent", client.getSingleplayerServer() != null);
                    result.addProperty("observedCustomMainMenu", customMenu);
                    System.out.println("[BOSS-QA] final-state screen=" + screen + " level=" + (client.level != null)
                            + " server=" + (client.getSingleplayerServer() != null) + " customMainMenu=" + customMenu);
                    BossQaIsolation.require(client.level == null
                            && client.getSingleplayerServer() == null && client.gui.screen() instanceof TitleScreen,
                            "Native isolated menu preference did not satisfy the genuine final state");
                });
            });
            context.runOnClient(client -> BossQaModules.checkDisabledReporting());
            JsonArray ids = new JsonArray();
            BossQaModules.registeredIds().forEach(ids::add);
            result.add("registeredIds", ids);
            result.addProperty("assertionsPassed", true);
            result.addProperty("frameworkExitStillRequired", true);
            writeResult();
            System.out.println("[BOSS-QA] ASSERTIONS_PASSED batch=" + batch + "; original Fabric final check and exit are still required");
        } catch (RuntimeException | Error failure) {
            result.addProperty("assertionsPassed", false);
            result.addProperty("failureType", failure.getClass().getName());
            try {
                writeResult();
            } catch (RuntimeException | Error markerFailure) {
                failure.addSuppressed(markerFailure);
            }
            throw failure;
        }
    }

    private void runWorldBatch(ClientGameTestContext context, String batch) {
        // Failed independent regressions remain FAIL and are rethrown after real teardown. A weapon
        // damage failure must not prevent the separately reset friend and movement controls running.
        List<AssertionError> independentFailures = new ArrayList<>();
        // World bootstrap/teardown use Fabric's native timeouts. Every fixture interaction has a 10s/200-tick bound.
        try (TestSingleplayerContext world = context.worldBuilder().setUseConsistentSettings(true).create()) {
            world.getClientLevel().waitForChunksDownload();
            runCase("offline-transitions-stay-off-on-world-join", () -> context.runOnClient(client -> BossQaModules.checkNewModulesDisabled()));
            try (BossQaNativeFixtures fixtures = new BossQaNativeFixtures(context)) {
                runCase("server-owned-arena-build-and-replication", fixtures::buildArena);
                if (batch.equals("damage-controls")) {
                    java.util.UUID[] targetId = new java.util.UUID[1];
                    runCase("native-server-player-for-damage-controls", () -> targetId[0] = fixtures.createPassiveTarget());
                    try (BossQaDamageControls controls = new BossQaDamageControls(context, fixtures, targetId[0])) {
                        runIndependentCase("native-held-vs-immediate-vs-delayed-damage-controls", controls::runAll, independentFailures);
                    }
                } else if (batch.equals("fixtures")) {
                    java.util.UUID[] targetId = new java.util.UUID[1];
                    runCase("native-server-player-replication-and-real-damage", () -> {
                        targetId[0] = fixtures.createPassiveTarget();
                        fixtures.proveNativeTargetDamage(targetId[0]);
                    });
                    try (BossQaCombatPackets packets = new BossQaCombatPackets(context, fixtures, targetId[0])) {
                        runIndependentCase("raw-client-packet-observes-target-selects-weapon-damages-and-restores", packets::proveRawAttack, independentFailures);
                        runIndependentCase("stored-friend-raw-packet-veto-with-native-teams-disabled", packets::proveStoredFriendVeto, independentFailures);
                    }
                    try (BossQaMovement movement = new BossQaMovement(context, fixtures)) {
                        runIndependentCase("fastladder-real-server-ascent-exceeds-disabled-control", movement::proveLadderAscent, independentFailures);
                        runIndependentCase("fastladder-sneak-cancels-boost-and-input-cleanup", movement::proveSneakCancel, independentFailures);
                    }
                } else {
                    runClutchCases(context, fixtures);
                }
            }
            runCase("native-fixture-cleanup-restores-new-modules-off", () -> context.runOnClient(client -> BossQaModules.checkNewModulesDisabled()));
        }
        if (!independentFailures.isEmpty()) {
            AssertionError first = independentFailures.getFirst();
            independentFailures.stream().skip(1).forEach(first::addSuppressed);
            throw first; // Never convert a captured failed assertion into a green batch/framework exit.
        }
    }

    private void runClutchCases(ClientGameTestContext context, BossQaNativeFixtures fixtures) {
        Module clutch = context.computeOnClient(client -> BossQaModules.require("autoclutch"));
        Map<String, String> previous = new LinkedHashMap<>();
        context.runOnClient(client -> clutch.settings().forEach(setting -> previous.put(setting.id(), clutch.value(setting.id()))));
        try {
            fixtures.setLocalLoadout(Map.of(0, new ItemStack(Items.STICK), 4, new ItemStack(Items.COBBLESTONE, 64)), 0);
            BossQaIsolation.require(fixtures.catchBlocks() == 0, "Fresh catch lane is contaminated");
            runCase("autoclutch-server-placement-consumption-and-landing", () -> {
                context.runOnClient(client -> {
                    clutch.setValueTransient("block", "Cobblestone");
                    clutch.setValueTransient("airPlace", "false");
                    clutch.setValueTransient("delay", "0");
                    clutch.setEnabled(true);
                    BossQaIsolation.require(clutch.isEnabled(), "AutoClutch refused to enable in a valid fixture world");
                });
                fixtures.startClutchFall();
                fixtures.awaitCatch();
                int placed = fixtures.catchBlocks();
                int remaining = fixtures.serverHotbarCount(4);
                BossQaIsolation.require(placed > 0 && remaining < 64, "No server-confirmed placement/item consumption");
                result.addProperty("clutchServerPlacedBlocks", placed);
                result.addProperty("clutchServerRemainingBlocks", remaining);
            });
            runCase("autoclutch-disable-restores-slot-and-releases-owner", () -> {
                context.runOnClient(client -> clutch.setEnabled(false));
                fixtures.awaitClient("clutch slot and ownership restored", client -> client.player.getInventory().getSelectedSlot() == 0
                        && !BossQaModules.slotHeld(clutch));
                fixtures.awaitSelectedSlot(0);
            });
            // Disable is tested before teleporting again; nothing sets the expected placement result for the module.
            runCase("autoclutch-disabled-fall-does-not-place", () -> {
                fixtures.clearCatchLaneAndReset();
                fixtures.setLocalLoadout(Map.of(0, new ItemStack(Items.STICK), 4, new ItemStack(Items.COBBLESTONE, 64)), 0);
                fixtures.startClutchFall();
                fixtures.awaitPlatformLanding();
                BossQaIsolation.require(fixtures.catchBlocks() == 0 && fixtures.serverHotbarCount(4) == 64,
                        "Disabled AutoClutch still placed a block or spent an item");
            });
        } finally {
            context.runOnClient(client -> {
                clutch.setEnabled(false);
                previous.forEach(clutch::setValueTransient);
            });
        }
    }

    private void runIndependentCase(String id, Runnable action, List<AssertionError> failures) {
        try {
            runCase(id, action);
        } catch (AssertionError failure) {
            failures.add(failure);
            System.out.println("[BOSS-QA] RECORDED_FAIL " + id + ": " + failure.getMessage());
        }
    }

    private void runCase(String id, Runnable action) {
        JsonObject entry = new JsonObject();
        entry.addProperty("id", id);
        cases.add(entry);
        try {
            action.run();
            entry.addProperty("status", "PASS");
            System.out.println("[BOSS-QA] PASS " + id);
        } catch (RuntimeException | Error failure) {
            entry.addProperty("status", "FAIL");
            throw failure;
        }
    }

    private void writeResult() {
        try {
            Files.writeString(BossQaIsolation.profile().resolve("boss-qa-result.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(result), StandardOpenOption.CREATE_NEW);
        } catch (IOException exception) {
            throw new AssertionError("Cannot write the isolated QA assertion result", exception);
        }
    }
}
