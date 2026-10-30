package com.boss.pvp.test;

import com.boss.pvp.module.combat.AutoWeaponModule;
import com.boss.pvp.util.CombatManager;
import com.boss.pvp.util.InventoryUtil;
import com.boss.pvp.util.input.HeldSlotManager;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Bounded measured native controls; no attributes, charge counters, or damage are manufactured. */
@SuppressWarnings("UnstableApiUsage")
final class BossQaDamageControls implements AutoCloseable {
    private final ClientGameTestContext context;
    private final BossQaNativeFixtures fixtures;
    private final UUID targetId;
    private final AutoWeaponModule weapon;
    private final boolean previousEnabled;
    private final String previousSwitchBack;
    private final String previousOnlyHotbar;
    private final JsonObject report = new JsonObject();
    private final JsonArray rows = new JsonArray();

    BossQaDamageControls(ClientGameTestContext context, BossQaNativeFixtures fixtures, UUID targetId) {
        this.context = context;
        this.fixtures = fixtures;
        this.targetId = targetId;
        BossQaIsolation.require("3".equals(System.getProperty("boss.qa.expectedApi")), "Damage controls are API3 only");
        weapon = context.computeOnClient(client -> (AutoWeaponModule) BossQaModules.require("autoweapon"));
        previousEnabled = context.computeOnClient(client -> weapon.isEnabled());
        previousSwitchBack = context.computeOnClient(client -> weapon.value("switchBack"));
        previousOnlyHotbar = context.computeOnClient(client -> weapon.value("onlyHotbar"));
        report.addProperty("schema", "boss-qa-native-damage-controls-v1");
        report.addProperty("runId", BossQaIsolation.profile().getFileName().toString());
        report.addProperty("pid", ProcessHandle.current().pid());
        report.addProperty("observer", "read-only test mixins at actual server packet and Player.attack HEAD/RETURN");
        report.addProperty("waitUnits", "native server ticks; actual interval measured from carried handler return to native Player.attack HEAD");
        report.add("controls", rows);
    }

    void runAll() {
        measure("A-held-diamond-25-server-ticks", "held", 25);
        measure("B-autoweapon-immediate", "auto", 0);
        measure("C-native-switch-wait-0", "switch", 0);
        measure("C-native-switch-wait-1", "switch", 1);
        measure("C-native-switch-wait-2", "switch", 2);
        measure("C-native-switch-wait-20", "switch", 20);
        JsonObject preliminaryHead = rows.get(0).getAsJsonObject().getAsJsonObject("nativeObservation").getAsJsonObject("playerAttackHead");
        double nativeDelay = preliminaryHead.get("currentItemAttackStrengthDelay").getAsDouble();
        if (Double.isFinite(nativeDelay) && nativeDelay >= 3 && nativeDelay <= 40) {
            int boundary = (int) Math.ceil(nativeDelay);
            measure("C-native-derived-charge-boundary-minus-one", "switch", boundary - 1);
            measure("C-native-derived-charge-boundary", "switch", boundary);
            measure("C-native-derived-charge-boundary-plus-one", "switch", boundary + 1);
        }
        report.addProperty("allRequestedNativeMeasurementsCompleted", true);
        report.addProperty("measuredRowCount", rows.size());
        JsonObject held = rows.get(0).getAsJsonObject();
        JsonObject immediate = rows.get(1).getAsJsonObject();
        double heldDamage = held.get("serverHealthDelta").getAsDouble();
        JsonObject heldHead = held.getAsJsonObject("nativeObservation").getAsJsonObject("playerAttackHead");
        boolean heldValid = heldDamage > 1.0 && heldHead.get("attackDamage").getAsDouble() > 1.0
                && heldHead.get("attackStrengthScaleHalf").getAsDouble() >= 1.0;
        report.addProperty("heldSwordNativeBaselineValid", heldValid);
        report.addProperty("immediateAutoWeaponDamageExceedsStick", immediate.get("serverHealthDelta").getAsDouble() > 1.0);
        Integer minimumObserved = null;
        for (int i = 2; i < rows.size(); i++) {
            JsonObject row = rows.get(i).getAsJsonObject();
            JsonObject trace = row.getAsJsonObject("nativeObservation");
            JsonObject head = trace.getAsJsonObject("playerAttackHead");
            boolean closeDamage = heldValid && Math.abs(row.get("serverHealthDelta").getAsDouble() - heldDamage) < 0.0001;
            boolean full = closeDamage && head.get("attackStrengthScaleHalf").getAsDouble() >= 1.0;
            row.addProperty("nearEqualHeldDamageWithinFloatTolerance", closeDamage);
            row.addProperty("nativeFullyChargedStrictScaleAtLeastOne", head.get("attackStrengthScaleHalf").getAsDouble() >= 1.0);
            row.addProperty("matchesObservedFullHeldDamage", full);
            if (full && row.has("actualServerTicksAfterCarried")) {
                int ticks = row.get("actualServerTicksAfterCarried").getAsInt();
                if (minimumObserved == null || ticks < minimumObserved) minimumObserved = ticks;
            }
        }
        if (minimumObserved != null) report.addProperty("minimumObservedNativeServerTicksForFullHeldDamage", minimumObserved);
        writeReport();
        BossQaIsolation.require(heldValid, "Held diamond sword still does not establish >1HP/full native charge: inspect recorded server loaded/tick/attribute state before changing production");
        BossQaIsolation.require(immediate.get("serverHealthDelta").getAsDouble() > 1.0,
                "Current AutoWeapon immediate bonus assertion still fails; actual matrix is recorded, not converted to PASS");
    }

    private void measure(String id, String mode, int delay) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("mode", mode);
        row.addProperty("requestedServerTicksAfterCarried", mode.equals("switch") ? delay : 0);
        row.addProperty("state", "PREPARING_NOT_MEASURED");
        rows.add(row);
        writeReport();
        boolean armed = false;
        try {
            context.runOnClient(client -> weapon.setEnabled(false));
            fixtures.clearDamageObservation();
            fixtures.resetCombatActors(targetId);
            fixtures.setLocalLoadout(mode.equals("held")
                    ? Map.of(0, new ItemStack(Items.DIAMOND_SWORD))
                    : Map.of(0, new ItemStack(Items.STICK), 5, new ItemStack(Items.DIAMOND_SWORD)), 0);
            row.add("clientBeforeNativeWarmup", context.computeOnClient(this::clientState));
            row.add("beforeNativeWarmup", fixtures.damageState(targetId));
            fixtures.waitServerTicks(targetId, 25);
            if (mode.equals("held")) fixtures.awaitHeldDiamondReady(0);
            row.add("afterNativeWarmup", fixtures.damageState(targetId));
            row.add("clientAfterNativeWarmup", context.computeOnClient(this::clientState));
            row.addProperty("heldBaselineUsedActualServerReadyPredicate", mode.equals("held"));
            if (mode.equals("auto")) {
                context.runOnClient(client -> {
                    weapon.setValueTransient("switchBack", "true");
                    weapon.setValueTransient("onlyHotbar", "true");
                    weapon.setEnabled(true);
                });
                fixtures.awaitClient("native AutoWeapon client charge ready", client -> weapon.chargeReadyForBestWeapon(visible(client)));
            }
            fixtures.beginDamageObservation(targetId, id);
            armed = true;
            if (mode.equals("switch") && delay > 0) {
                context.runOnClient(client -> InventoryUtil.selectHotbarSlot(client, 5));
                CompletableFuture<Void> sent = new CompletableFuture<>();
                fixtures.scheduleAfterSwordSwitchTicks(delay, () -> {
                    try {
                        sendMeasuredAttack(Minecraft.getInstance(), row, mode, delay);
                        sent.complete(null);
                    } catch (RuntimeException | Error failure) {
                        sent.completeExceptionally(failure);
                    }
                });
                fixtures.awaitClient("scheduled actual client attack sent", client -> sent.isDone());
                sent.join(); // A captured client exception is rethrown, never treated as a pass.
            } else {
                context.runOnClient(client -> sendMeasuredAttack(client, row, mode, delay));
            }
            fixtures.waitDamageObservation();
            if (mode.equals("auto")) {
                fixtures.awaitClient("natural AutoWeapon restore after observed native attack", client ->
                        client.player.getInventory().getSelectedSlot() == 0 && !HeldSlotManager.holds(weapon));
                row.addProperty("naturalClientRestoreAndLeaseReleaseObserved", true);
            }
            // A real ordered packet barrier retains all earlier carried-item events in the trace.
            context.runOnClient(client -> InventoryUtil.selectHotbarSlot(client, 2));
            fixtures.awaitSelectedSlot(2);
            JsonObject trace = fixtures.finishDamageObservation();
            armed = false;
            row.add("nativeObservation", trace);
            BossQaIsolation.require(trace.get("packetEntries").getAsInt() == 1 && trace.get("playerAttackEntries").getAsInt() == 1,
                    "Control must observe exactly one real native server packet and Player.attack, not a fake health change");
            JsonObject before = trace.getAsJsonObject("playerAttackHead");
            JsonObject after = trace.getAsJsonObject("playerAttackReturn");
            double delta = before.get("targetHealth").getAsDouble() - after.get("targetHealth").getAsDouble();
            row.addProperty("serverHealthDelta", delta);
            if (trace.has("firstSwordSwitchServerTick")) row.addProperty("actualServerTicksAfterCarried",
                    before.get("serverTick").getAsInt() - trace.get("firstSwordSwitchServerTick").getAsInt());
            row.addProperty("state", "NATIVE_MEASUREMENT_RECORDED");
            System.out.println("[BOSS-QA-DAMAGE] " + id + " selected=" + before.get("serverSelectedSlot")
                    + " hand=" + before.get("serverMainHand") + " damageAttribute=" + before.get("attackDamage")
                    + " speed=" + before.get("attackSpeed") + " scaleHalf=" + before.get("attackStrengthScaleHalf")
                    + " serverTick=" + before.get("serverTick") + " playerTick=" + before.get("attackerTick")
                    + " loaded=" + before.get("attackerClientLoaded") + " frozen=" + before.get("tickRateFrozen")
                    + " healthDelta=" + delta);
            writeReport();
        } catch (RuntimeException | AssertionError failure) {
            row.addProperty("state", "FAILED_NATIVE_CONTROL");
            row.addProperty("failure", failure.toString());
            writeReport();
            throw failure;
        } finally {
            try {
                if (armed) {
                    row.add("partialNativeObservation", fixtures.finishDamageObservation());
                    writeReport();
                }
            } finally {
                context.runOnClient(client -> {
                    weapon.setEnabled(false);
                    InventoryUtil.selectHotbarSlot(client, 0);
                });
                fixtures.awaitSelectedSlot(0);
            }
        }
    }

    private void sendMeasuredAttack(Minecraft client, JsonObject row, String mode, int delay) {
        BossQaIsolation.require(client.isSameThread(), "Native attack must be sent on the actual client thread");
        BossQaIsolation.require(weapon.isEnabled() == mode.equals("auto"), "Wrong real AutoWeapon state for control");
        if (mode.equals("switch") && delay == 0) InventoryUtil.selectHotbarSlot(client, 5);
        row.add("clientBeforeSend", clientState(client));
        Player target = visible(client);
        client.getConnection().send(new ServerboundAttackPacket(target.getId()));
        row.add("clientAfterSend", clientState(client));
        if (mode.equals("auto")) {
            BossQaIsolation.require(client.player.getInventory().getSelectedSlot() == 5
                    && HeldSlotManager.holds(weapon) && CombatManager.currentTarget(client) == target,
                    "Immediate AutoWeapon did not synchronously select/observe the native target");
        }
    }

    private Player visible(Minecraft client) {
        Player target = client.level == null ? null : client.level.getPlayerByUUID(targetId);
        BossQaIsolation.require(target != null && target.isAlive() && client.player != null && client.player.distanceTo(target) < 3,
                "Damage target is not the replicated real server player in range");
        return target;
    }

    private JsonObject clientState(Minecraft client) {
        JsonObject out = new JsonObject();
        out.addProperty("tick", client.player.tickCount);
        out.addProperty("selectedSlot", client.player.getInventory().getSelectedSlot());
        out.addProperty("mainHand", client.player.getMainHandItem().getItem().toString());
        out.addProperty("attackDamage", client.player.getAttributeValue(Attributes.ATTACK_DAMAGE));
        out.addProperty("attackSpeed", client.player.getAttributeValue(Attributes.ATTACK_SPEED));
        out.addProperty("attackStrengthScaleHalf", client.player.getAttackStrengthScale(0.5f));
        out.addProperty("autoWeaponEnabled", weapon.isEnabled());
        out.addProperty("autoWeaponLease", HeldSlotManager.holds(weapon));
        return out;
    }

    private void writeReport() {
        try {
            Files.writeString(BossQaIsolation.profile().resolve("damage-controls-native.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch (IOException failure) {
            throw new AssertionError("Cannot persist actual native damage-control evidence", failure);
        }
    }

    @Override
    public void close() {
        try {
            fixtures.clearDamageObservation();
        } finally {
            context.runOnClient(client -> {
                weapon.setEnabled(false);
                weapon.setValueTransient("switchBack", previousSwitchBack);
                weapon.setValueTransient("onlyHotbar", previousOnlyHotbar);
                weapon.setEnabled(previousEnabled);
            });
        }
    }
}
