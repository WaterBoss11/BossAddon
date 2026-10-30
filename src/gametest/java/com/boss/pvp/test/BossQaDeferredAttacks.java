package com.boss.pvp.test;

import autismclient.modules.Module;
import autismclient.util.AutismConfig;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.combat.AutoWeaponModule;
import com.boss.pvp.util.CombatManager;
import com.boss.pvp.util.InventoryUtil;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.PvpUtil;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

/** New deferred implementation only. Every damage/count observation comes from real native server hooks. */
@SuppressWarnings("UnstableApiUsage")
final class BossQaDeferredAttacks implements AutoCloseable {
    private final ClientGameTestContext context;
    private final BossQaNativeFixtures fixtures;
    private final UUID targetId;
    private final AutoWeaponModule weapon;
    private final Map<String, String> savedSettings;
    private final boolean savedEnabled;
    private final AutismConfig.ModuleState teams;
    private final boolean savedTeamsEnabled;
    private final String savedFriends;
    private final JsonObject report = new JsonObject();
    private final JsonArray rows = new JsonArray();
    // Only the client thread reads/writes active telemetry; it is detached before serializing the report.
    private JsonArray samples;
    private Module successor;
    private int preparingTicks;
    private boolean preparingLeaseLost;
    private boolean sharedTargetObserved;
    private boolean restoredGapObserved;
    private boolean successorObserved;
    private boolean successorBeforeRestore;
    private boolean armed;
    private long requestedAt;
    private long previousAttackMs;

    BossQaDeferredAttacks(ClientGameTestContext context, BossQaNativeFixtures fixtures, UUID targetId) {
        this.context = context;
        this.fixtures = fixtures;
        this.targetId = targetId;
        BossQaIsolation.require("3".equals(System.getProperty("boss.qa.expectedApi")), "Deferred batch is restricted to API3");
        weapon = context.computeOnClient(client -> BossPvpAddon.autoWeapon);
        BossQaIsolation.require(weapon != null, "Actual registered AutoWeapon is missing");
        savedEnabled = context.computeOnClient(client -> weapon.isEnabled());
        savedSettings = context.computeOnClient(client -> Map.of("switchBack", weapon.value("switchBack"),
                "onlyHotbar", weapon.value("onlyHotbar"), "settleMs", weapon.value("settleMs")));
        teams = context.computeOnClient(client -> AutismConfig.getGlobal().modules.get("teams"));
        BossQaIsolation.require(teams != null && teams.settings != null, "Native Teams config is absent");
        savedTeamsEnabled = teams.enabled;
        savedFriends = teams.settings.get("friends");
        context.runOnClient(client -> {
            BossQaIsolation.require(BossPvpAddon.autoMace != null && !BossPvpAddon.autoMace.isEnabled(),
                    "New AutoMace must remain disabled throughout the deferred AutoWeapon regression");
            teams.enabled = false;
            teams.settings.put("friends", "");
            ClientTickEvents.END_CLIENT_TICK.register(this::observeTick);
        });
        report.addProperty("schema", "boss-qa-deferred-attacks-v1");
        report.addProperty("runId", BossQaIsolation.profile().getFileName().toString());
        report.addProperty("pid", ProcessHandle.current().pid());
        report.addProperty("scope", "new deferred implementation; existing 17 native controls not rerun");
        report.addProperty("nativeObserver", "real ServerGamePacketListenerImpl.handleAttack and server Player.attack HEAD/RETURN");
        report.add("cases", rows);
    }

    void runAll() {
        // Cancellations precede the positive test so their evidence survives a first positive production failure.
        run("manual-hotbar-cancels-without-overwriting-slot-2", row -> cancellation(row, "manual"));
        run("inventory-gui-cancels-and-restores-slot-0", row -> cancellation(row, "gui"));
        run("priority-88-autoclutch-request-unwinds-before-successor", row -> cancellation(row, "preempt"));
        run("one-raw-request-native-7hp-and-natural-restore", row -> positive(row, 1, false));
        run("four-cancelled-host-requests-coalesce-to-one-native-7hp", row -> positive(row, 4, false));
        run("already-held-charged-diamond-is-immediate-native-7hp", row -> positive(row, 1, true));
        run("genuine-gravity-falling-charged-mace-is-immediate", this::fallingMace);
        report.addProperty("allFocusedAssertionsPassed", true);
        writeReport();
    }

    @FunctionalInterface
    private interface Scenario { void run(JsonObject row); }

    private void run(String id, Scenario scenario) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("status", "RUNNING");
        rows.add(row);
        writeReport();
        try {
            scenario.run(row);
            row.addProperty("status", "PASS");
            System.out.println("[BOSS-QA-DEFERRED] PASS " + id + " health=" + row.get("actualHealth")
                    + " packets=" + row.get("actualPacketCount") + " attacks=" + row.get("actualAttackCount")
                    + " clientSlot=" + row.getAsJsonObject("clientAfter").get("selectedSlot")
                    + " serverSlot=" + row.getAsJsonObject("serverAfter").get("serverSelectedSlot"));
        } catch (RuntimeException | Error failure) {
            row.addProperty("status", "FAIL");
            row.addProperty("failure", failure.toString());
            StringWriter stack = new StringWriter();
            failure.printStackTrace(new PrintWriter(stack));
            row.addProperty("failureStack", stack.toString());
            System.out.println("[BOSS-QA-DEFERRED] FAIL " + id + ": " + failure);
            throw failure; // Stop immediately; never continue into another production scenario after failure.
        } finally {
            context.runOnClient(client -> {
                samples = null;
                row.addProperty("preparingNativeClientTicks", preparingTicks);
                row.addProperty("preparingLeaseLost", preparingLeaseLost);
                row.addProperty("sharedTargetObserved", sharedTargetObserved);
                row.addProperty("restoredGapBeforeSuccessor", restoredGapObserved);
                row.addProperty("successorObserved", successorObserved);
                row.addProperty("successorBeforeRestore", successorBeforeRestore);
                row.add("clientBeforeCleanup", clientState(client));
            });
            try {
                if (armed) {
                    row.add("partialNativeObservation", fixtures.finishDamageObservation());
                    armed = false;
                }
                row.add("serverBeforeCleanup", fixtures.damageState(targetId));
                writeReport();
            } finally {
                context.runOnClient(client -> {
                    weapon.setEnabled(false);
                    if (successor != null) HeldSlotManager.clear(successor);
                    successor = null;
                    client.gui.setScreen(null);
                });
            }
        }
    }

    private void prepare(JsonObject row, boolean held, boolean mace) {
        context.runOnClient(client -> {
            weapon.setEnabled(false);
            client.gui.setScreen(null);
        });
        fixtures.clearDamageObservation();
        fixtures.resetCombatActors(targetId);
        fixtures.setLocalLoadout(held ? Map.of(0, new ItemStack(Items.DIAMOND_SWORD))
                : Map.of(0, new ItemStack(Items.STICK), 4, new ItemStack(mace ? Items.MACE : Items.DIAMOND_SWORD)), 0);
        fixtures.waitServerTicks(targetId, mace ? 45 : 25);
        if (held) fixtures.awaitHeldDiamondReady(0);
        context.runOnClient(client -> {
            client.player.setYRot(0);
            client.player.setXRot(0);
            weapon.setValueTransient("switchBack", "true");
            weapon.setValueTransient("onlyHotbar", "true");
            weapon.setValueTransient("settleMs", savedSettings.get("settleMs"));
            weapon.setEnabled(true);
            BossQaIsolation.require(!PvpUtil.isProtectedPlayer(client, visible(client)), "Fresh native target is protected");
        });
        fixtures.awaitClient("actual chosen-weapon native charge before one input", client -> weapon.chargeReadyForBestWeapon(visible(client)));
        row.add("serverBefore", fixtures.damageState(targetId));
        row.add("clientBefore", context.computeOnClient(this::clientState));
        BossQaIsolation.require(fixtures.targetHealth(targetId) == 20.0f, "Native target must start at exactly 20 HP");
        fixtures.beginDamageObservation(targetId, row.get("id").getAsString());
        armed = true;
        context.runOnClient(client -> {
            preparingTicks = 0;
            preparingLeaseLost = sharedTargetObserved = restoredGapObserved = successorObserved = successorBeforeRestore = false;
            successor = null;
            samples = new JsonArray();
            row.add("nativeClientTickSamples", samples);
            previousAttackMs = CombatManager.lastAttackMs();
        });
    }

    private void sendRaw(Minecraft client, JsonObject row, int count, boolean immediate) {
        Player target = visible(client);
        requestedAt = System.nanoTime();
        row.addProperty("rawInputCount", count);
        row.addProperty("requestClientTick", client.player.tickCount);
        row.addProperty("requestNanoTime", requestedAt);
        for (int i = 0; i < count; i++) client.getConnection().send(new ServerboundAttackPacket(target.getId()));
        boolean accepted = CombatManager.lastAttackMs() > previousAttackMs;
        row.addProperty("synchronouslyAccepted", accepted);
        row.add("clientImmediatelyAfterRaw", clientState(client));
        BossQaIsolation.require(accepted == immediate, "Raw request had wrong immediate/deferred acceptance: " + row);
        BossQaIsolation.require(HeldSlotManager.holds(weapon), "Raw request failed to acquire priority-20 weapon lease");
        if (!immediate) BossQaIsolation.require(client.player.getInventory().getSelectedSlot() == 4
                && "Preparing".equals(weapon.info()), "Cancelled raw input did not retain the selected slot-4 weapon and bounded intent");
        if (accepted) sharedTargetObserved |= CombatManager.currentTarget(client) == target;
    }

    private void cancellation(JsonObject row, String reason) {
        prepare(row, false, false);
        context.runOnClient(client -> {
            sendRaw(client, row, 1, false);
            if (reason.equals("manual")) InventoryUtil.selectHotbarSlot(client, 2);
            else if (reason.equals("gui")) client.gui.setScreen(new InventoryScreen(client.player));
            else {
                successor = BossPvpAddon.autoClutch; // The actual module's higher-priority arbiter request, not a fake attack.
                BossQaIsolation.require(successor != null && !successor.isEnabled(), "AutoClutch request fixture must remain disabled");
                HeldSlotManager.request(successor, HeldSlotManager.PRIORITY_AUTOCLUTCH);
            }
        });
        int wanted = reason.equals("manual") ? 2 : 0;
        fixtures.awaitClient("deferred cancellation releases weapon without replacing user selection", client ->
                !HeldSlotManager.holds(weapon) && client.player.getInventory().getSelectedSlot() == wanted && weapon.info().isEmpty());
        fixtures.awaitSelectedSlot(wanted);
        if (reason.equals("preempt")) {
            fixtures.awaitClient("native endTick grants successor only after restored unwind gap", client -> successorObserved);
            context.runOnClient(client -> {
                BossQaIsolation.require(restoredGapObserved && !successorBeforeRestore,
                        "Priority successor acquired before the weapon's slot-0 restoration/unwind gap");
                HeldSlotManager.clear(successor);
                successor = null;
            });
        }
        finishQuiet(row);
        BossQaIsolation.require(row.get("actualPacketCount").getAsInt() == 0 && row.get("actualAttackCount").getAsInt() == 0
                && row.get("actualHealth").getAsFloat() == 20.0f, "Cancelled deferred intent reached native server or damaged target: " + row);
        context.runOnClient(client -> BossQaIsolation.require(CombatManager.lastAttackMs() == previousAttackMs
                && !HeldSlotManager.holds(weapon) && client.player.getInventory().getSelectedSlot() == wanted,
                "Cancellation sent/retained attack or overwrote manually chosen slot"));
        if (reason.equals("gui")) context.runOnClient(client -> client.gui.setScreen(null));
    }

    private void positive(JsonObject row, int rawCount, boolean held) {
        prepare(row, held, false);
        context.runOnClient(client -> sendRaw(client, row, rawCount, held));
        fixtures.waitDamageObservation();
        fixtures.awaitClient("automatic single native attack restores both weapon state and slot", client ->
                !HeldSlotManager.holds(weapon) && client.player.getInventory().getSelectedSlot() == 0 && weapon.info().isEmpty());
        fixtures.awaitSelectedSlot(0);
        finishQuiet(row);
        assertSingle(row);
        JsonObject head = row.getAsJsonObject("nativeObservation").getAsJsonObject("playerAttackHead");
        BossQaIsolation.require(row.get("actualHealth").getAsFloat() == 13.0f
                && head.get("attackDamage").getAsDouble() == 7.0
                && head.get("attackStrengthScaleHalf").getAsDouble() >= 1.0,
                "Native deferred/held diamond must deal exactly 7 HP at real full charge, never the old 1 HP: " + row);
        context.runOnClient(client -> {
            BossQaIsolation.require(sharedTargetObserved, "Accepted native attack was never observed through shared CombatManager target");
            if (!held) BossQaIsolation.require(preparingTicks > 5 && !preparingLeaseLost,
                    "Deferred weapon did not retain/renew its real lease beyond the five-tick arbiter expiry");
        });
        if (!held) {
            int switched = firstSlotFourTick(row);
            int waited = head.get("serverTick").getAsInt() - switched;
            row.addProperty("actualServerTicksAfterWeaponSelection", waited);
            BossQaIsolation.require(waited >= (int) Math.ceil(head.get("currentItemAttackStrengthDelay").getAsDouble()),
                    "Native attack arrived before the data-derived full chosen-weapon cooldown");
        }
    }

    private void fallingMace(JsonObject row) {
        prepare(row, false, true);
        fixtures.startNaturalMaceFall(); // No fall-distance, motion, attack-ticker or attribute fabrication.
        boolean[] sent = {false};
        fixtures.awaitClient("one genuine falling/charged/in-range mace collision window", client -> {
            Player target = visible(client);
            if (!MaceItem.canSmashAttack(client.player) || !weapon.chargeReadyForBestWeapon(target)
                    || !client.player.isWithinAttackRange(client.player.getInventory().getItem(4), target.getBoundingBox(), 0.0)) return false;
            if (!sent[0]) {
                row.add("naturalClientFallAtInput", clientState(client));
                sendRaw(client, row, 1, true);
                sent[0] = true;
            }
            return true;
        });
        fixtures.waitDamageObservation();
        fixtures.awaitClient("immediate natural mace releases/restores without pending replay", client ->
                !HeldSlotManager.holds(weapon) && client.player.getInventory().getSelectedSlot() == 0);
        fixtures.awaitSelectedSlot(0);
        finishQuiet(row);
        assertSingle(row);
        JsonObject head = row.getAsJsonObject("nativeObservation").getAsJsonObject("playerAttackHead");
        row.addProperty("actualServerTicksAfterWeaponSelection", head.get("serverTick").getAsInt() - firstSlotFourTick(row));
        BossQaIsolation.require(head.get("serverMainHand").getAsString().equals("minecraft:mace")
                && head.get("attackerFallDistance").getAsDouble() > 1.5
                && head.get("attackStrengthScaleHalf").getAsDouble() >= 1.0
                && row.get("actualServerTicksAfterWeaponSelection").getAsInt() <= 1
                && row.get("actualHealth").getAsFloat() < 20,
                "Falling mace did not preserve genuine immediate/native smash timing/state: " + row);
    }

    private void finishQuiet(JsonObject row) {
        // Outlast the production three-second pending limit, while the original server observer remains armed.
        fixtures.awaitServer("native no-extra-hit observation through pending deadline", () -> System.nanoTime() - requestedAt >= 3_200_000_000L);
        row.addProperty("observedRequestAgeNanos", System.nanoTime() - requestedAt);
        row.add("nativeObservation", fixtures.finishDamageObservation());
        armed = false;
        JsonObject trace = row.getAsJsonObject("nativeObservation");
        row.addProperty("actualPacketCount", trace.get("packetEntries").getAsInt());
        row.addProperty("actualAttackCount", trace.get("playerAttackEntries").getAsInt());
        row.addProperty("actualHealth", fixtures.targetHealth(targetId));
        row.add("serverAfter", fixtures.damageState(targetId));
        row.add("clientAfter", context.computeOnClient(this::clientState));
    }

    private void assertSingle(JsonObject row) {
        BossQaIsolation.require(row.get("actualPacketCount").getAsInt() == 1 && row.get("actualAttackCount").getAsInt() == 1,
                "Expected exactly one real native server attack, without a helper-generated second click: " + row);
        BossQaIsolation.require(row.getAsJsonObject("serverAfter").get("serverSelectedSlot").getAsInt() == 0
                && row.getAsJsonObject("clientAfter").get("selectedSlot").getAsInt() == 0
                && !row.getAsJsonObject("clientAfter").get("weaponLease").getAsBoolean(), "Native attack did not naturally restore slot 0/lease");
    }

    private int firstSlotFourTick(JsonObject row) {
        for (var element : row.getAsJsonObject("nativeObservation").getAsJsonArray("events")) {
            JsonObject event = element.getAsJsonObject();
            if (event.get("kind").getAsString().equals("carried-slot-return") && event.get("requestedSlot").getAsInt() == 4)
                return event.get("serverTick").getAsInt();
        }
        throw new AssertionError("No real native server carried-slot-4 event was observed");
    }

    private Player visible(Minecraft client) {
        Player target = client.level == null ? null : client.level.getPlayerByUUID(targetId);
        BossQaIsolation.require(client.player != null && target != null && target.isAlive(), "Real replicated native server target missing");
        return target;
    }

    private JsonObject clientState(Minecraft client) {
        JsonObject out = new JsonObject();
        out.addProperty("tick", client.player.tickCount);
        out.addProperty("nanoTime", System.nanoTime());
        out.addProperty("selectedSlot", client.player.getInventory().getSelectedSlot());
        out.addProperty("mainHand", client.player.getMainHandItem().getItem().toString());
        out.addProperty("attackDamage", client.player.getAttributeValue(Attributes.ATTACK_DAMAGE));
        out.addProperty("attackSpeed", client.player.getAttributeValue(Attributes.ATTACK_SPEED));
        out.addProperty("attackStrengthScaleHalf", client.player.getAttackStrengthScale(0.5f));
        out.addProperty("currentItemAttackStrengthDelay", client.player.getCurrentItemAttackStrengthDelay());
        out.addProperty("onGround", client.player.onGround());
        out.addProperty("fallDistance", client.player.fallDistance);
        out.addProperty("y", client.player.getY());
        out.addProperty("weaponLease", HeldSlotManager.holds(weapon));
        out.addProperty("autoMaceEnabled", BossPvpAddon.autoMace.isEnabled());
        out.addProperty("weaponInfo", weapon.info());
        out.addProperty("lastAttackMs", CombatManager.lastAttackMs());
        out.addProperty("sharedTarget", CombatManager.currentTarget(client) == client.level.getPlayerByUUID(targetId));
        out.addProperty("screen", client.gui.screen() == null ? "none" : client.gui.screen().getClass().getName());
        return out;
    }

    private void observeTick(Minecraft client) {
        if (samples == null || client.player == null || client.level == null) return;
        if ("Preparing".equals(weapon.info())) {
            preparingTicks++;
            preparingLeaseLost |= !HeldSlotManager.holds(weapon);
        }
        sharedTargetObserved |= CombatManager.currentTarget(client) == client.level.getPlayerByUUID(targetId);
        if (successor != null) {
            boolean nextOwns = HeldSlotManager.holds(successor);
            if (!HeldSlotManager.holds(weapon) && !nextOwns && client.player.getInventory().getSelectedSlot() == 0) restoredGapObserved = true;
            if (nextOwns) {
                successorObserved = true;
                successorBeforeRestore |= !restoredGapObserved || client.player.getInventory().getSelectedSlot() != 0;
            }
        }
        if (samples.size() < 210) {
            JsonObject sample = clientState(client);
            sample.addProperty("successorLease", HeldSlotManager.holds(successor));
            samples.add(sample);
        }
    }

    private void writeReport() {
        try {
            Files.writeString(BossQaIsolation.profile().resolve("deferred-attacks-native.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch (IOException failure) {
            throw new AssertionError("Cannot preserve focused native deferred evidence", failure);
        }
    }

    @Override
    public void close() {
        context.runOnClient(client -> {
            samples = null;
            weapon.setEnabled(false);
            if (successor != null) HeldSlotManager.clear(successor);
            successor = null;
            client.gui.setScreen(null);
            savedSettings.forEach(weapon::setValueTransient);
            weapon.setEnabled(savedEnabled);
            teams.enabled = savedTeamsEnabled;
            if (savedFriends == null) teams.settings.remove("friends"); else teams.settings.put("friends", savedFriends);
        });
        fixtures.clearDamageObservation();
    }
}
