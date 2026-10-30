package com.boss.pvp.test;

import autismclient.modules.Module;
import autismclient.util.AutismConfig;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.combat.AutoMaceModule;
import com.boss.pvp.util.InventoryUtil;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.NativeAimBridge;
import com.boss.pvp.util.pvp.PvpUtil;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/** One bounded API3 batch. No direct attack/jab, motion/fall/attribute/ticker writes, or host aim invocation. */
@SuppressWarnings("UnstableApiUsage")
final class BossQaAutoMaceNative20261018 implements AutoCloseable {
    // API4 intentionally does not expose these implementation classes. This API3-only fixture uses
    // ONLY their actual public methods; no private method/field access and no target/aim forcing.
    private final java.lang.reflect.Method hostLookup;
    private final java.lang.reflect.Method mouseGuard;
    private final java.lang.reflect.Method antiBotGuard;
    private final net.minecraft.network.syncher.EntityDataAccessor<Byte> livingFlags;
    private final ClientGameTestContext context;
    private final BossQaNativeFixtures fixtures;
    private final UUID targetId;
    private final UUID localId;
    private final MinecraftServer server;
    private final AutoMaceModule mace;
    private final Module hostAim;
    private final AutismConfig.ModuleState teams;
    private final boolean oldTeamsEnabled;
    private final String oldFriends;
    private final Map<String, String> oldSettings = new LinkedHashMap<>();
    private final JsonObject initialHost;
    private final JsonObject initialDiskHost;
    private final JsonObject report = new JsonObject();
    private final JsonArray cases = new JsonArray();
    // Each telemetry buffer belongs exclusively to its named thread and is detached before serialization.
    private JsonArray clientSamples;
    private JsonArray serverSamples;
    private String intervention;
    private boolean intervened;
    private boolean gapObserved;
    private boolean successorObserved;
    private JsonObject interventionState;
    private boolean observerArmed;
    private ServerPlayer friend;
    private Connection friendConnection;
    private EmbeddedChannel friendChannel;
    private Channel observedServerChannel;
    private Connection servicedTargetConnection;
    private volatile String packetCase;
    private JsonArray nativePacketArrivals;
    private final ConcurrentLinkedQueue<PacketArrival> packetQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong packetSequence = new AtomicLong();
    private static final String PACKET_OBSERVER = "boss-qa-automace-support-20261018";
    private record PacketArrival(String caseId, long sequence, long nanoTime, String kind, int slot) { }

    BossQaAutoMaceNative20261018(ClientGameTestContext context, BossQaNativeFixtures fixtures, UUID targetId) {
        this.context = context;
        this.fixtures = fixtures;
        this.targetId = targetId;
        BossQaIsolation.require("3".equals(System.getProperty("boss.qa.expectedApi")), "AutoMace native fixture is API3 only; no API4/auth launch");
        // These native implementation classes may initialize a Minecraft singleton. Resolve them on
        // the real client thread, not in test-thread field initializers (Fabric enforces this).
        hostLookup = context.computeOnClient(client -> publicNativeMethod("autismclient.modules.ModuleRegistry", "get", String.class));
        mouseGuard = context.computeOnClient(client -> publicNativeMethod("autismclient.util.AutismMouseInputSimulator", "canUseMouseLook"));
        antiBotGuard = context.computeOnClient(client -> publicNativeMethod("autismclient.modules.AutismAntiBot", "suppress", net.minecraft.world.entity.Entity.class));
        livingFlags = context.computeOnClient(client -> nativeLivingFlagsAccessor());
        server = context.computeOnClient(Minecraft::getSingleplayerServer);
        localId = context.computeOnClient(client -> client.player.getUUID());
        mace = context.computeOnClient(client -> BossPvpAddon.autoMace);
        hostAim = context.computeOnClient(client -> nativeModule("aim-assist"));
        teams = context.computeOnClient(client -> AutismConfig.getGlobal().modules.get("teams"));
        BossQaIsolation.require(server != null && !server.isPublished() && mace != null && hostAim != null && teams != null,
                "Missing actual native server/module/host AimAssist/Teams");
        oldTeamsEnabled = teams.enabled;
        oldFriends = teams.settings.get("friends");
        context.runOnClient(client -> {
            BossQaIsolation.require(!mace.isEnabled() && !hostAim.isEnabled() && !BossPvpAddon.autoWeapon.isEnabled(),
                    "Fresh AutoMace and native AimAssist/AutoWeapon must be OFF");
            BossQaIsolation.require(NativeAimBridge.available(), "Actual Module-constructor capture did not provide NativeAimBridge");
            mace.settings().forEach(setting -> oldSettings.put(setting.id(), mace.value(setting.id())));
            teams.enabled = false;
            teams.settings.put("friends", "");
            // Persist the already-initialized native defaults in this NEW profile before comparing disk snapshots.
            // No native AimAssist enablement or setting value is changed by the fixture.
            AutismConfig.getGlobal().save();
            ClientTickEvents.END_CLIENT_TICK.register(this::observeClient);
        });
        initialHost = context.computeOnClient(client -> hostSnapshot());
        initialDiskHost = diskHost();
        ServerTickEvents.END_SERVER_TICK.register(this::observeServer);
        report.addProperty("schema", "boss-qa-automace-native-20261018-v1");
        report.addProperty("runId", BossQaIsolation.profile().getFileName().toString());
        report.addProperty("pid", ProcessHandle.current().pid());
        report.addProperty("scope", "separate AutoMace: actual borrowed native aim, native gravity mace, cancellations, native enchanted-spear Lunge; no old deferred matrix");
        report.addProperty("nativeAimBridgeAvailable", true);
        report.addProperty("autoMaceInitiallyEnabled", false);
        report.addProperty("directAttackOrJabHelperCalls", 0);
        report.addProperty("manufacturedFallMotionAttributesOrCharge", false);
        report.add("hostAimBefore", initialHost.deepCopy());
        report.add("hostAimDiskBefore", initialDiskHost.deepCopy());
        report.add("cases", cases);
    }

    void runAll() {
        run("basic-native-Instant-aim-and-real-gravity-mace", this::basic);
        run("AutoMace-off-real-fall-no-automatic-attack", this::disabledFall);
        run("manual-slot-2-during-preparation-is-preserved", row -> cancel(row, "manual"));
        run("inventory-GUI-during-preparation-restores-slot-0", row -> cancel(row, "gui"));
        run("priority-88-preempt-restores-before-successor", row -> cancel(row, "preempt"));
        run("Lunge-III-real-native-ground-jab-and-server-dash", row -> lunge(row, "positive"));
        run("unenchanted-spear-does-not-auto-lunge", row -> lunge(row, "unenchanted"));
        run("hunger-6-does-not-auto-lunge", row -> lunge(row, "hunger6"));
        run("ground-opt-in-off-does-not-auto-lunge", row -> lunge(row, "groundOff"));
        run("native-protected-friend-in-pierce-line-veto", this::friendRay);
        report.addProperty("allFocusedAssertionsPassed", true);
        report.addProperty("optionalAxeStunAndAxeAttributesExecuted", false);
        report.addProperty("ordinarySpearReachPositiveAndAirborneLungeToMaceExecuted", false);
        writeReport();
    }

    /** Explicit selection never presents a one-case regression as the whole support batch. */
    void runSupportBatch() {
        String selection = System.getProperty("boss.qa.supportCase", "all");
        BossQaIsolation.require(selection.equals("all") || selection.equals("axe-attributes"), "Unknown support-case selection: " + selection);
        boolean attributesOnly = selection.equals("axe-attributes");
        report.addProperty("scope", attributesOnly ? "one attribute-swap strike-window regression only; other support cases not rerun"
                : "three support cases: real shield axe-to-mace, independent axeAttributes, plain spear reach");
        report.addProperty("maximumCaseCount", attributesOnly ? 1 : 3);
        report.addProperty("supportCase", selection);
        writeReport();
        installSupportPacketObserver();
        if (!attributesOnly) run("native-equipped-using-shield-axe-stun-then-MACE", this::shieldAxeMace);
        run("independent-axeAttributes-real-axe-to-MACE", this::independentAxeAttributes);
        if (!attributesOnly) run("plain-held-charged-spear-real-reach-jab-no-out-of-range-MACE", this::plainSpearReach);
        report.addProperty("allFocusedAssertionsPassed", true);
        report.addProperty("airborneLungeToMaceExecuted", false);
        writeReport();
    }

    private void shieldAxeMace(JsonObject row) {
        Vec3 floor = new Vec3(0.5, 81, -2.4);
        prepare(row, false, false, 20, floor, 5, true);
        requireGenuineHeldCharge(row, 5, Items.DIAMOND_AXE);
        serviceNativeTargetConnection(row);
        fixtures.onServer("stop use and equip actual shield BEFORE its native use-flag edge", () -> {
            ServerPlayer other = target();
            other.stopUsingItem();
            other.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            other.inventoryMenu.sendAllDataToRemote();
            other.setYRot(180.0f); // Face the approaching local actor; no blocking-state field is written.
            other.setXRot(0.0f);
            return null;
        });
        // Native onSyncedDataUpdated snapshots CURRENT equipment only on the USING edge.
        // Reproduce ordinary equip-before-RMB: never let the client latch EMPTY before equipment arrives.
        fixtures.awaitClient("actual equipped shield replicated BEFORE native start-use", client ->
                visible(client).getOffhandItem().is(Items.SHIELD) && !visible(client).isUsingItem());
        row.add("clientEquippedBeforeNativeUse", context.computeOnClient(this::clientState));
        row.add("serverEquippedBeforeNativeUse", fixtures.onServer("capture native equipped/not-using barrier", this::serverState));
        fixtures.onServer("native ServerPlayer.startUsingItem AFTER acknowledged client equipment", () -> {
            target().startUsingItem(InteractionHand.OFF_HAND);
            return null;
        });
        fixtures.awaitServer("genuine native shield has reached its blocking window", () -> target().isUsingItem()
                && target().getUseItem().is(Items.SHIELD) && target().isBlocking()
                && !target().getCooldowns().isOnCooldown(target().getOffhandItem()));
        fixtures.awaitClient("actual shield use/blocking metadata replicated", client -> visible(client).isUsingItem()
                && visible(client).getUseItem().is(Items.SHIELD) && visible(client).isBlocking());
        context.runOnClient(client -> {
            mace.setValueTransient("axeStun", "true");
            mace.setValueTransient("axeAttributes", "false");
            mace.setValueTransient("axeSlot", "5");
            mace.setValueTransient("axeCharge", "0.9"); // Native already-held/full charge; user control, not a ticker write.
            mace.setValueTransient("confirmStun", "true"); // Keep native-confirmed behavior, not a fabricated unblock.
            mace.setValueTransient("stunDelay", "0");
            mace.setValueTransient("point", "Head");
            row.add("actualTestSettings", supportSettings());
            row.add("clientBeforeFall", clientState(client));
            mace.setEnabled(true);
        });
        row.add("serverShieldBeforeFall", fixtures.onServer("capture actual shield preconditions", this::serverState));
        startSupportFall(row, new Vec3(floor.x, 85, floor.z));
        fixtures.waitDamageObservation(); // The first genuine axe packet, not an injected/helper attack.
        row.add("authoritativeShieldDisabled", fixtures.onServer("await native authoritative shield disable", () -> {
            BossQaIsolation.require(!target().isBlocking() && !target().isUsingItem()
                    && target().getCooldowns().isOnCooldown(target().getOffhandItem()),
                    "Real axe packet returned without authoritative shield disable");
            return serverState();
        }));
        fixtures.awaitTargetDamage(targetId, 20.0f);
        fixtures.awaitClient("native shield-to-mace sequence restores original axe slot and releases", client ->
                client.player.getInventory().getSelectedSlot() == 5 && !HeldSlotManager.holds(mace));
        fixtures.awaitSelectedSlot(5);
        fixtures.awaitPlatformLanding();
        fixtures.waitServerTicks(targetId, 20);
        finish(row);
        JsonArray heads = attackEvents(row, "player-attack-head");
        BossQaIsolation.require(heads.size() == 2 && row.getAsJsonObject("nativeObservation").get("packetEntries").getAsInt() == 2
                && countArrivals(row, "ATTACK") == 2 && countArrivals(row, "STAB") == 0,
                "Shield sequence must have exactly real axe then real mace, with no helper click/extra attack");
        JsonObject axe = heads.get(0).getAsJsonObject();
        JsonObject smash = heads.get(1).getAsJsonObject();
        row.add("actualAxeAttackHead", axe.deepCopy());
        row.add("actualMaceAttackHead", smash.deepCopy());
        BossQaIsolation.require("minecraft:diamond_axe".equals(axe.get("serverMainHand").getAsString())
                && axe.get("serverSelectedSlot").getAsInt() == 5 && axe.get("attackStrengthScale0").getAsDouble() >= 0.9
                && axe.get("attackerFallDistance").getAsDouble() > 1.5,
                "First native server strike was not the genuinely charged/actually falling held axe");
        requireRealMaceDamage(row, smash);
        JsonObject shield = row.getAsJsonObject("serverShieldBeforeFall");
        BossQaIsolation.require(shield.get("targetBlocking").getAsBoolean() && shield.get("targetUsingItem").getAsBoolean()
                && "minecraft:shield".equals(shield.get("targetUseItem").getAsString()), "Shield actor was not truly using its equipped shield");
        requireRestored(row, 5);
        assertHostUnchanged();
    }

    private void independentAxeAttributes(JsonObject row) {
        Vec3 floor = new Vec3(0.5, 81, -2.4);
        prepare(row, false, false, 20, floor, 5, true);
        requireGenuineHeldCharge(row, 5, Items.DIAMOND_AXE);
        context.runOnClient(client -> {
            mace.setValueTransient("axeAttributes", "true");
            mace.setValueTransient("axeStun", "false");
            mace.setValueTransient("axeSlot", "5");
            mace.setValueTransient("point", "Head");
            row.add("actualTestSettings", supportSettings());
            row.add("clientBeforeFall", clientState(client));
            mace.setEnabled(true);
        });
        startSupportFall(row, new Vec3(floor.x, 85, floor.z));
        fixtures.waitDamageObservation();
        fixtures.awaitClient("independent axeAttributes sequence naturally restores slot 5/lease", client ->
                client.player.getInventory().getSelectedSlot() == 5 && !HeldSlotManager.holds(mace));
        fixtures.awaitSelectedSlot(5);
        fixtures.awaitPlatformLanding();
        fixtures.waitServerTicks(targetId, 20);
        finish(row);
        JsonArray heads = attackEvents(row, "player-attack-head");
        BossQaIsolation.require(heads.size() == 1 && row.getAsJsonObject("nativeObservation").get("packetEntries").getAsInt() == 1
                && countArrivals(row, "ATTACK") == 1 && countArrivals(row, "STAB") == 0,
                "Independent axeAttributes must issue only one actual mace hit, not an axe-stun attack");
        JsonObject smash = heads.get(0).getAsJsonObject();
        row.add("actualMaceAttackHead", smash.deepCopy());
        row.add("actualServerCachedAttackDamageAtMace", smash.get("attackDamage").deepCopy());
        boolean verifyAttributeWindow = "axe-attributes".equals(System.getProperty("boss.qa.supportCase"));
        row.addProperty("experimentalBorrowedAttributeBonusRequired", verifyAttributeWindow);
        if (verifyAttributeWindow) {
            double heldAxeDamage = row.getAsJsonObject("genuinelyHeldChargedServer").get("attackDamage").getAsDouble();
            BossQaIsolation.require(Math.abs(smash.get("attackDamage").getAsDouble() - heldAxeDamage) < 0.00001,
                    "Mace attack did not retain the genuinely held axe's native attribute within the strike window");
        }
        requireRealMaceDamage(row, smash);
        boolean preparedAxe = false;
        for (var element : row.getAsJsonArray("nativeClientTicks")) {
            JsonObject sample = element.getAsJsonObject();
            preparedAxe |= sample.get("maceLease").getAsBoolean() && sample.get("selectedSlot").getAsInt() == 5
                    && "Axe preparation".equals(sample.get("autoMaceInfo").getAsString());
        }
        row.addProperty("actualIndependentAxePreparationObserved", preparedAxe);
        BossQaIsolation.require(preparedAxe, "Independent axeAttributes path did not actually pre-equip/prepare the real axe");
        requireRestored(row, 5);
        assertHostUnchanged();
    }

    private void plainSpearReach(JsonObject row) {
        Vec3 floor = new Vec3(0.5, 81, -4.5); // 3.5 horizontal blocks: real MACE range veto, native spear item range.
        prepare(row, true, false, 20, floor, 6, false);
        requireGenuineHeldCharge(row, 6, Items.DIAMOND_SPEAR);
        row.add("serverBeforeFall", fixtures.onServer("check actual native spear and mace ranges", () -> {
            BossQaIsolation.require(local().getMainHandItem().getEnchantments().isEmpty()
                    && local().getMainHandItem().has(DataComponents.PIERCING_WEAPON)
                    && !local().isWithinAttackRange(local().getInventory().getItem(4), target().getBoundingBox(), 0)
                    && local().isWithinAttackRange(local().getMainHandItem(), target().getBoundingBox(), 0),
                    "Plain spear fixture is not genuinely unenchanted/outside MACE/inside native spear range");
            return serverState();
        }));
        context.runOnClient(client -> {
            mace.setValueTransient("spearReach", "true");
            mace.setValueTransient("spearJab", "true");
            mace.setValueTransient("spearSlot", "6");
            mace.setValueTransient("autoLunge", "false");
            mace.setValueTransient("point", "Head");
            row.add("actualTestSettings", supportSettings());
            row.add("clientBeforeFall", clientState(client));
            mace.setEnabled(true);
        });
        startSupportFall(row, new Vec3(floor.x, 84, floor.z));
        fixtures.awaitTargetDamage(targetId, 20.0f); // Native piercing path, NOT the standard Player.attack observer.
        row.add("authoritativeSpearDamageState", fixtures.onServer("read real health/equipment after native spear jab", this::serverState));
        fixtures.awaitPlatformLanding();
        fixtures.awaitClient("plain spear sequence restores original slot on real landing", client ->
                client.player.getInventory().getSelectedSlot() == 6 && !HeldSlotManager.holds(mace));
        fixtures.awaitSelectedSlot(6);
        fixtures.waitServerTicks(targetId, 20);
        finish(row);
        JsonObject trace = row.getAsJsonObject("nativeObservation");
        row.addProperty("actualNativeStabPackets", countArrivals(row, "STAB"));
        BossQaIsolation.require(countArrivals(row, "STAB") == 1 && countArrivals(row, "ATTACK") == 0
                && trace.get("packetEntries").getAsInt() == 0 && trace.get("playerAttackEntries").getAsInt() == 0
                && row.getAsJsonObject("serverAfter").get("targetHealth").getAsFloat() < 20.0f,
                "Plain spear must generate exactly one actual native STAB/real server damage and zero out-of-range MACE attacks");
        boolean illegalMaceWindow = false;
        for (var element : row.getAsJsonArray("nativeServerTicks")) {
            JsonObject sample = element.getAsJsonObject();
            illegalMaceWindow |= sample.get("maceRangeLegal").getAsBoolean();
        }
        row.addProperty("maceRangeEverLegalDuringTrial", illegalMaceWindow);
        BossQaIsolation.require(!illegalMaceWindow, "3.5-horizontal fixture unexpectedly entered native MACE range");
        requireRestored(row, 6);
        assertHostUnchanged();
    }

    private void serviceNativeTargetConnection(JsonObject row) {
        row.add("nativeActorConnectionService", fixtures.onServer("register only the owned mock connection in vanilla network servicing", () -> {
            try {
                var field = ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
                field.setAccessible(true); // Read only Minecraft's actor connection, never a private host method.
                Connection connection = (Connection) field.get(target().connection);
                var actualConnections = server.getConnection().getConnections();
                boolean added = false;
                synchronized (actualConnections) {
                    if (!actualConnections.contains(connection)) {
                        actualConnections.add(connection);
                        servicedTargetConnection = connection;
                        added = true;
                    }
                }
                // Native ServerConnectionListener.tick -> Connection.tick -> ServerGamePacketListenerImpl.tick
                // -> ServerPlayer.doTick advances the real use/block delay. No fixture tick()/doTick(),
                // use-ticker, attribute, cooldown or blocking-state writes are used.
                JsonObject proof = new JsonObject();
                proof.addProperty("registeredInActualVanillaConnectionList", actualConnections.contains(connection));
                proof.addProperty("addedOnlyThisOwnedConnection", added);
                proof.addProperty("fixtureManualActorTickCalls", 0);
                proof.addProperty("fixtureUseOrCooldownTickerWrites", 0);
                return proof;
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("Cannot register the owned native actor's real connection for vanilla servicing", failure);
            }
        }));
    }

    private void requireGenuineHeldCharge(JsonObject row, int slot, net.minecraft.world.item.Item item) {
        fixtures.awaitServer("genuine already-held support weapon full native server charge", () ->
                local().getInventory().getSelectedSlot() == slot && local().getMainHandItem().is(item)
                        && local().getAttackStrengthScale(0) >= 1.0f);
        fixtures.awaitClient("genuine already-held support weapon full native client charge", client ->
                client.player.getInventory().getSelectedSlot() == slot && client.player.getMainHandItem().is(item)
                        && client.player.getAttackStrengthScale(0) >= 1.0f);
        row.add("genuinelyHeldChargedServer", fixtures.onServer("record actual held charge/attributes", this::serverState));
    }

    private void startSupportFall(JsonObject row, Vec3 start) {
        row.add("serverTeleportOnlyFallStart", fixtures.onServer("start owned arena gravity fall by teleport only", () -> {
            local().teleportTo(start.x, start.y, start.z);
            return serverState();
        }));
    }

    private JsonObject supportSettings() {
        JsonObject out = new JsonObject();
        for (String key : new String[]{"axeStun", "axeAttributes", "axeSlot", "axeHold", "axeCharge", "confirmStun", "stunDelay",
                "maceCharge", "minFall", "restoreSlot", "spearReach", "spearJab", "spearSlot", "spearHold", "autoLunge", "point", "smooth"})
            out.addProperty(key, mace.value(key));
        return out;
    }

    private JsonArray attackEvents(JsonObject row, String kind) {
        JsonArray out = new JsonArray();
        for (var value : row.getAsJsonObject("nativeObservation").getAsJsonArray("events"))
            if (kind.equals(value.getAsJsonObject().get("kind").getAsString())) out.add(value.deepCopy());
        return out;
    }

    private int countArrivals(JsonObject row, String kind) {
        int count = 0;
        for (var value : row.getAsJsonArray("actualServerPacketArrivals"))
            if (kind.equals(value.getAsJsonObject().get("kind").getAsString())) count++;
        return count;
    }

    private void requireRealMaceDamage(JsonObject row, JsonObject head) {
        BossQaIsolation.require("minecraft:mace".equals(head.get("serverMainHand").getAsString())
                && head.get("serverSelectedSlot").getAsInt() == 4 && head.get("attackerFallDistance").getAsDouble() > 1.5,
                "No actual native falling MACE hit was observed");
        boolean actualHealthReduction = false;
        for (var value : attackEvents(row, "player-attack-return")) {
            JsonObject returned = value.getAsJsonObject();
            if ("minecraft:mace".equals(returned.get("serverMainHand").getAsString()))
                actualHealthReduction |= returned.get("targetHealth").getAsFloat() < head.get("targetHealth").getAsFloat();
        }
        BossQaIsolation.require(actualHealthReduction, "The actual server MACE attack returned without a real health reduction");
    }

    @FunctionalInterface private interface Scenario { void run(JsonObject row); }

    private void run(String id, Scenario action) {
        JsonObject row = new JsonObject();
        row.addProperty("id", id);
        row.addProperty("status", "RUNNING");
        cases.add(row);
        writeReport();
        try {
            action.run(row);
            row.addProperty("status", "PASS");
            System.out.println("[BOSS-QA-AUTOMACE] PASS " + id + " server=" + row.get("serverAfter"));
        } catch (RuntimeException | Error failure) {
            row.addProperty("status", "FAIL");
            row.addProperty("failure", failure.toString());
            StringWriter stack = new StringWriter();
            failure.printStackTrace(new PrintWriter(stack));
            row.addProperty("failureStack", stack.toString());
            System.out.println("[BOSS-QA-AUTOMACE] FAIL " + id + ": " + failure);
            throw failure; // First concrete failure stops the entire focused batch.
        } finally {
            row.add("serverAtScenarioExit", fixtures.onServer("capture actual native state before any scenario cleanup", this::serverState));
            context.runOnClient(client -> {
                row.add("clientBeforeCleanup", clientState(client));
                clientSamples = null;
                intervention = null;
                mace.setEnabled(false);
                HeldSlotManager.clear(BossPvpAddon.autoClutch);
                client.gui.setScreen(null);
            });
            row.add("serverBeforeCleanup", fixtures.onServer("detach native AutoMace server telemetry", () -> {
                drainSupportPackets();
                JsonObject state = serverState();
                serverSamples = null;
                packetCase = null; nativePacketArrivals = null; packetQueue.clear();
                return state;
            }));
            if (observerArmed) {
                row.add("partialNativeObservation", fixtures.finishDamageObservation());
                observerArmed = false;
            }
            row.add("hostAimAfter", context.computeOnClient(client -> hostSnapshot()));
            row.add("hostAimDiskAfter", diskHost());
            writeReport();
        }
    }

    private void prepare(JsonObject row, boolean spear, boolean enchanted, int food, Vec3 start) {
        prepare(row, spear, enchanted, food, start, 0, false);
    }

    private void prepare(JsonObject row, boolean spear, boolean enchanted, int food, Vec3 start, int originalSlot, boolean axe) {
        context.runOnClient(client -> {
            mace.setEnabled(false);
            HeldSlotManager.clear(BossPvpAddon.autoClutch);
            client.gui.setScreen(null);
            oldSettings.forEach(mace::setValueTransient);
            mace.setValueTransient("smooth", "Instant");
            mace.setValueTransient("aimSettings", "AutoMace");
            mace.setValueTransient("point", "Body");
            mace.setValueTransient("safeZone", "0");
            mace.setValueTransient("fov", "160");
            mace.setValueTransient("horizontalSpeed", "100");
            mace.setValueTransient("verticalSpeed", "100");
            teams.enabled = false;
            teams.settings.put("friends", "");
            client.options.keyAttack.setDown(false);
            client.options.keyUse.setDown(false);
            client.options.keyUp.setDown(false);
            client.options.keyJump.setDown(false);
        });
        fixtures.clearDamageObservation();
        fixtures.onServer("teleport only and restore legitimate native actors/hunger", () -> {
            ServerPlayer local = local();
            ServerPlayer target = target();
            local.setGameMode(GameType.SURVIVAL);
            local.setHealth(local.getMaxHealth());
            local.getFoodData().setFoodLevel(food);
            local.getFoodData().setSaturation(food == 20 ? 5.0f : 0.0f);
            local.teleportTo(start.x, start.y, start.z);
            target.stopUsingItem(); // Setup only; never called between the shield's native start-use and attacks.
            target.getInventory().clearContent();
            target.inventoryMenu.sendAllDataToRemote();
            target.setHealth(target.getMaxHealth());
            target.teleportTo(BossQaNativeFixtures.TARGET_START.x, BossQaNativeFixtures.TARGET_START.y, BossQaNativeFixtures.TARGET_START.z);
            return null;
        });
        Map<Integer, ItemStack> loadout = new LinkedHashMap<>();
        loadout.put(0, new ItemStack(Items.STICK));
        loadout.put(4, new ItemStack(Items.MACE));
        if (axe) loadout.put(5, new ItemStack(Items.DIAMOND_AXE));
        if (spear) {
            ItemStack high = fixtures.onServer("create actual native spear enchantment loadout", () -> {
                ItemStack stack = new ItemStack(Items.DIAMOND_SPEAR);
                if (enchanted) stack.enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LUNGE), 3);
                return stack;
            });
            loadout.put(6, high);
            if (enchanted) {
                ItemStack low = fixtures.onServer("create lower native Lunge competitor", () -> {
                    ItemStack stack = new ItemStack(Items.DIAMOND_SPEAR);
                    stack.enchant(server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.LUNGE), 1);
                    return stack;
                });
                loadout.put(7, low);
            }
        }
        fixtures.setLocalLoadout(loadout, originalSlot);
        fixtures.awaitClient("teleport, hunger and grounded native survival replication", client -> client.player.onGround()
                && client.player.position().distanceToSqr(start) < 0.15 && client.player.getFoodData().getFoodLevel() == food);
        fixtures.waitServerTicks(targetId, 30); // Real invulnerability/charge expiry, never ticker writes.
        context.runOnClient(client -> {
            client.setCameraEntity(client.player);
            GLFW.glfwShowWindow(client.getWindow().handle());
            GLFW.glfwFocusWindow(client.getWindow().handle());
            client.mouseHandler.grabMouse();
            client.player.setYRot(28.0f); // Genuine initial offset, BEFORE arming. No later fixture rotation writes.
            client.player.setXRot(-8.0f);
        });
        fixtures.awaitClient("owned native window focus and grabbed camera input", client -> client.isWindowActive()
                && GLFW.glfwGetWindowAttrib(client.getWindow().handle(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE
                && client.mouseHandler.isMouseGrabbed() && (Boolean) invokePublicNative(mouseGuard)
                && client.getCameraEntity() == client.player && client.gui.screen() == null && !client.isPaused());
        context.waitTicks(2); // Let the genuine offset reach the server before its teleport-only fall.
        row.add("clientBefore", context.computeOnClient(this::clientState));
        row.add("serverBefore", fixtures.onServer("read unmodified native preconditions", this::serverState));
        BossQaIsolation.require(fixtures.targetHealth(targetId) == 20.0f, "Native target must start at 20 HP");
        context.runOnClient(client -> {
            BossQaIsolation.require(!PvpUtil.isProtectedPlayer(client, visible(client)) && !hostAim.isEnabled(), "Fresh target protected or host AimAssist enabled");
            intervened = gapObserved = successorObserved = false;
            interventionState = null;
            clientSamples = new JsonArray();
            row.add("nativeClientTicks", clientSamples);
        });
        fixtures.onServer("arm read-only per-server-tick telemetry", () -> {
            serverSamples = new JsonArray();
            row.add("nativeServerTicks", serverSamples);
            if (observedServerChannel != null) {
                packetQueue.clear();
                packetSequence.set(0);
                nativePacketArrivals = new JsonArray();
                row.add("actualServerPacketArrivals", nativePacketArrivals);
                packetCase = row.get("id").getAsString();
            }
            return null;
        });
        fixtures.beginDamageObservation(targetId, row.get("id").getAsString());
        observerArmed = true;
    }

    private void basic(JsonObject row) {
        prepare(row, false, false, 20, BossQaNativeFixtures.LOCAL_START);
        context.runOnClient(client -> mace.setEnabled(true));
        fixtures.startNaturalMaceFall(); // Arena teleport only; native client/server gravity creates every fall-distance value.
        fixtures.waitDamageObservation();
        fixtures.awaitClient("AutoMace restores original slot/clears lease after real smash", client ->
                client.player.getInventory().getSelectedSlot() == 0 && !HeldSlotManager.holds(mace));
        fixtures.awaitSelectedSlot(0);
        fixtures.awaitPlatformLanding();
        fixtures.waitServerTicks(targetId, 25);
        finish(row);
        JsonObject trace = row.getAsJsonObject("nativeObservation");
        JsonObject head = trace.getAsJsonObject("playerAttackHead");
        BossQaIsolation.require(trace.get("packetEntries").getAsInt() == 1 && trace.get("playerAttackEntries").getAsInt() == 1,
                "AutoMace must generate exactly one real server hit without any physical/helper click: " + trace);
        BossQaIsolation.require(head != null && head.get("serverMainHand").getAsString().equals("minecraft:mace")
                && head.get("attackerFallDistance").getAsDouble() > 1.5
                && row.getAsJsonObject("serverAfter").get("targetHealth").getAsFloat() < 20.0f,
                "Actual server did not observe a MACE/native fall >1.5/real health reduction: " + trace);
        requireRestored(row, 0);
        boolean yawMoved = false, pitchMoved = false, lookHit = false, nativeCrosshair = false;
        for (var value : row.getAsJsonArray("nativeClientTicks")) {
            JsonObject sample = value.getAsJsonObject();
            yawMoved |= Math.abs(sample.get("yaw").getAsDouble() - 28.0) > 5;
            pitchMoved |= Math.abs(sample.get("pitch").getAsDouble() + 8.0) > 5;
            lookHit |= sample.get("lookIntersectsTarget").getAsBoolean();
            nativeCrosshair |= sample.get("nativeCrosshairTarget").getAsBoolean();
        }
        row.addProperty("actualYawMovedFromOffset", yawMoved);
        row.addProperty("actualPitchMovedFromOffset", pitchMoved);
        row.addProperty("actualCurrentLookIntersectsTarget", lookHit);
        row.addProperty("actualNativeCrosshairTarget", nativeCrosshair);
        BossQaIsolation.require(yawMoved && pitchMoved && lookHit && nativeCrosshair,
                "Borrowed native Instant aim did not produce actual yaw/pitch AND current-look/native crosshair target");
        assertHostUnchanged();
    }

    private void disabledFall(JsonObject row) {
        prepare(row, false, false, 20, BossQaNativeFixtures.LOCAL_START);
        fixtures.startNaturalMaceFall();
        fixtures.awaitPlatformLanding();
        fixtures.waitServerTicks(targetId, 25);
        finish(row);
        requireNoHit(row);
        requireRestored(row, 0);
        assertHostUnchanged();
    }

    private void cancel(JsonObject row, String reason) {
        prepare(row, false, false, 20, BossQaNativeFixtures.LOCAL_START);
        context.runOnClient(client -> {
            mace.setValueTransient("minFall", "3.5");
            intervention = reason;
            mace.setEnabled(true);
        });
        fixtures.startNaturalMaceFall();
        fixtures.awaitClient("real preparation reached and requested cancellation", client -> intervened);
        int wanted = "manual".equals(reason) ? 2 : 0;
        fixtures.awaitClient("actual AutoMace cancellation releases lease without overwriting choice", client ->
                !HeldSlotManager.holds(mace) && client.player.getInventory().getSelectedSlot() == wanted);
        if ("preempt".equals(reason)) {
            fixtures.awaitClient("priority-88 successor follows the restored unwind gap", client -> successorObserved);
            BossQaIsolation.require(gapObserved, "Priority-88 successor acquired before original slot restoration");
            context.runOnClient(client -> HeldSlotManager.clear(BossPvpAddon.autoClutch));
        }
        fixtures.awaitSelectedSlot(wanted);
        context.runOnClient(client -> client.gui.setScreen(null));
        fixtures.awaitPlatformLanding();
        fixtures.waitServerTicks(targetId, 25);
        row.add("actualPreparationAtIntervention", interventionState);
        row.addProperty("unwindGapObserved", gapObserved);
        row.addProperty("successorObserved", successorObserved);
        finish(row);
        requireNoHit(row);
        requireRestored(row, wanted);
        assertHostUnchanged();
    }

    private void lunge(JsonObject row, String kind) {
        boolean positive = "positive".equals(kind);
        prepare(row, true, !"unenchanted".equals(kind), "hunger6".equals(kind) ? 6 : 20, new Vec3(0.5, 81, -6));
        context.runOnClient(client -> {
            mace.setValueTransient("autoLunge", "true");
            mace.setValueTransient("lungeOnGround", Boolean.toString(!"groundOff".equals(kind)));
            mace.setEnabled(true);
        });
        if (positive) {
            fixtures.awaitServer("native Lunge spends real spear durability", () -> local().getInventory().getItem(6).getDamageValue() > 0);
            fixtures.awaitServer("native Lunge moves authoritative position at least one horizontal block", () -> horizontalTravel(serverState(), row.getAsJsonObject("serverBefore")) > 1.0);
            fixtures.awaitClient("ground Lunge pulse restores original slot and lease", client ->
                    client.player.getInventory().getSelectedSlot() == 0 && !HeldSlotManager.holds(mace));
            fixtures.awaitSelectedSlot(0);
            // Disable only AFTER the pulse has restored naturally, before another legitimate delay expires.
            context.runOnClient(client -> mace.setEnabled(false));
            fixtures.waitServerTicks(targetId, 5);
        } else fixtures.waitServerTicks(targetId, 32);
        finish(row);
        JsonObject after = row.getAsJsonObject("serverAfter");
        if (positive) {
            boolean realImpulse = false, foodCost = false, selectedHighest = false;
            JsonObject before = row.getAsJsonObject("serverBefore");
            for (var value : row.getAsJsonArray("nativeServerTicks")) {
                JsonObject sample = value.getAsJsonObject();
                realImpulse |= sample.get("horizontalVelocity").getAsDouble() > 0.15;
                foodCost |= sample.get("foodExhaustion").getAsDouble() > before.get("foodExhaustion").getAsDouble() + 0.05
                        || sample.get("saturation").getAsDouble() < before.get("saturation").getAsDouble();
            }
            for (var value : row.getAsJsonObject("nativeObservation").getAsJsonArray("events")) {
                JsonObject event = value.getAsJsonObject();
                selectedHighest |= "carried-slot-return".equals(event.get("kind").getAsString()) && event.get("requestedSlot").getAsInt() == 6;
            }
            row.addProperty("actualNativeHorizontalImpulse", realImpulse);
            row.addProperty("actualNativeFoodCost", foodCost);
            row.addProperty("highestLungeLevelSlot6Selected", selectedHighest);
            row.addProperty("actualServerHorizontalTravel", horizontalTravel(after, before));
            BossQaIsolation.require(realImpulse && foodCost && selectedHighest && after.get("spear6Damage").getAsInt() > 0
                    && after.get("spear7Damage").getAsInt() == 0 && horizontalTravel(after, before) > 1.0,
                    "No genuine native POST_PIERCING_ATTACK Lunge impulse/food/durability/displacement proof");
            BossQaIsolation.require(row.getAsJsonObject("nativeObservation").get("packetEntries").getAsInt() == 0,
                    "Ground Lunge incorrectly issued a MACE/entity attack outside mace range");
        } else {
            requireNoHit(row);
            BossQaIsolation.require(after.get("spear6Damage").getAsInt() == 0
                    && horizontalTravel(after, row.getAsJsonObject("serverBefore")) < 0.05,
                    "Guarded negative produced automatic native Lunge movement/durability cost");
        }
        requireRestored(row, 0);
        assertHostUnchanged();
    }

    private void friendRay(JsonObject row) {
        prepare(row, true, true, 20, new Vec3(0.5, 81, -6));
        UUID friendId = fixtures.onServer("register second actual native protected player in pierce line", () -> {
            UUID id = UUID.nameUUIDFromBytes(("automace-native-friend:" + BossQaIsolation.profile().getFileName()).getBytes(StandardCharsets.UTF_8));
            GameProfile profile = new GameProfile(id, "MaceQaFriend");
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
            friend = new ServerPlayer(server, server.overworld(), profile, cookie.clientInformation());
            friendConnection = new Connection(PacketFlow.SERVERBOUND);
            friendChannel = new EmbeddedChannel(friendConnection);
            server.getPlayerList().placeNewPlayer(friendConnection, friend, cookie);
            friend.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
            friend.setGameMode(GameType.SURVIVAL);
            friend.teleportTo(0.5, 81, -3.5);
            return id;
        });
        fixtures.awaitClient("real protected player is replicated in native pierce line", client -> client.level.getPlayerByUUID(friendId) != null);
        context.runOnClient(client -> {
            teams.settings.put("friends", "MaceQaFriend");
            BossQaIsolation.require(PvpUtil.isProtectedPlayer(client, client.level.getPlayerByUUID(friendId)), "Native friend protection was not active");
            mace.setValueTransient("autoLunge", "true");
            mace.setValueTransient("lungeOnGround", "true");
            mace.setEnabled(true);
        });
        fixtures.awaitClient("actual spear preparation with protected bystander in current look", client ->
                HeldSlotManager.holds(mace) && client.player.getInventory().getSelectedSlot() == 6
                        && rayHits(client, visible(client), 6.0) && rayHits(client, client.level.getPlayerByUUID(friendId), 6.0));
        fixtures.waitServerTicks(targetId, 26);
        row.addProperty("actualProtectedFriendHealth", fixtures.onServer("read actual protected friend's health", () -> friend.getHealth()));
        context.runOnClient(client -> mace.setEnabled(false));
        fixtures.awaitSelectedSlot(0);
        finish(row);
        requireNoHit(row);
        requireRestored(row, 0);
        BossQaIsolation.require(row.get("actualProtectedFriendHealth").getAsFloat() == 20.0f
                && row.getAsJsonObject("serverAfter").get("spear6Damage").getAsInt() == 0
                && horizontalTravel(row.getAsJsonObject("serverAfter"), row.getAsJsonObject("serverBefore")) < 0.05,
                "Protected friend pierce-line veto leaked an actual jab/dash/hit");
        assertHostUnchanged();
    }

    private void finish(JsonObject row) {
        context.runOnClient(client -> { row.add("clientAfter", clientState(client)); clientSamples = null; });
        row.add("serverAfter", fixtures.onServer("detach and read authoritative final state", () -> {
            drainSupportPackets();
            JsonObject state = serverState(); serverSamples = null;
            packetCase = null; nativePacketArrivals = null; packetQueue.clear();
            return state;
        }));
        row.add("nativeObservation", fixtures.finishDamageObservation());
        observerArmed = false;
    }

    private void requireNoHit(JsonObject row) {
        JsonObject trace = row.getAsJsonObject("nativeObservation");
        BossQaIsolation.require(trace.get("packetEntries").getAsInt() == 0 && trace.get("playerAttackEntries").getAsInt() == 0
                && row.getAsJsonObject("serverAfter").get("targetHealth").getAsFloat() == 20.0f,
                "Negative unexpectedly reached the real server with an attack/damage: " + trace);
    }

    private void requireRestored(JsonObject row, int slot) {
        BossQaIsolation.require(row.getAsJsonObject("clientAfter").get("selectedSlot").getAsInt() == slot
                && !row.getAsJsonObject("clientAfter").get("maceLease").getAsBoolean()
                && row.getAsJsonObject("serverAfter").get("serverSelectedSlot").getAsInt() == slot,
                "Client/server slot or lease was not restored/preserved");
    }

    private void observeClient(Minecraft client) {
        if (clientSamples == null || client.player == null || client.level == null) return;
        if (clientSamples.size() < 200) clientSamples.add(clientState(client));
        if (intervention != null && !intervened && HeldSlotManager.holds(mace) && client.player.getInventory().getSelectedSlot() == 4) {
            interventionState = clientState(client);
            intervened = true;
            if ("manual".equals(intervention)) InventoryUtil.selectHotbarSlot(client, 2);
            else if ("gui".equals(intervention)) client.gui.setScreen(new InventoryScreen(client.player));
            else HeldSlotManager.request(BossPvpAddon.autoClutch, HeldSlotManager.PRIORITY_AUTOCLUTCH);
        }
        if ("preempt".equals(intervention) && intervened) {
            if (!HeldSlotManager.holds(mace) && !HeldSlotManager.holds(BossPvpAddon.autoClutch)
                    && client.player.getInventory().getSelectedSlot() == 0) gapObserved = true;
            if (HeldSlotManager.holds(BossPvpAddon.autoClutch)) successorObserved = true;
        }
    }

    private record ObservedWire(Connection connection, Channel channel) { }

    /** Passive decoded-packet observation on ONLY this fresh integrated-server connection. */
    private void installSupportPacketObserver() {
        ObservedWire wire = fixtures.onServer("locate only the owned in-memory server connection for passive observation", () -> {
            BossQaIsolation.require(!server.isPublished() && local().connection.hasClientLoaded(), "Native packet probe requires the owned unpublished loaded actor");
            try {
                // Read Minecraft's connection/channel fields only. No private AUTISM method or field is accessed,
                // no listener/packet is replaced, and no game state is written by this observation setup.
                var connectionField = ServerCommonPacketListenerImpl.class.getDeclaredField("connection");
                connectionField.setAccessible(true);
                Connection connection = (Connection) connectionField.get(local().connection);
                BossQaIsolation.require(connection.isMemoryConnection(), "Refuse a live/public/non-memory connection");
                var channelField = Connection.class.getDeclaredField("channel");
                channelField.setAccessible(true);
                return new ObservedWire(connection, (Channel) channelField.get(connection));
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("Cannot observe actual owned native connection", failure);
            }
        });
        observedServerChannel = wire.channel();
        var future = wire.channel().eventLoop().submit(() -> {
            ChannelHandlerContext anchor = wire.channel().pipeline().context(wire.connection());
            BossQaIsolation.require(anchor != null && wire.channel().pipeline().get(PACKET_OBSERVER) == null,
                    "Actual native connection pipeline anchor missing/already observed");
            wire.channel().pipeline().addBefore(anchor.name(), PACKET_OBSERVER, new ChannelInboundHandlerAdapter() {
                @Override public void channelRead(ChannelHandlerContext ctx, Object message) {
                    try {
                        String active = packetCase;
                        if (active != null) {
                            String kind = null;
                            int slot = -1;
                            if (message instanceof ServerboundAttackPacket) kind = "ATTACK";
                            else if (message instanceof ServerboundPlayerActionPacket action
                                    && action.getAction() == ServerboundPlayerActionPacket.Action.STAB) kind = "STAB";
                            else if (message instanceof ServerboundSetCarriedItemPacket carried) {
                                kind = "CARRIED"; slot = carried.getSlot();
                            }
                            if (kind != null) packetQueue.add(new PacketArrival(active, packetSequence.incrementAndGet(), System.nanoTime(), kind, slot));
                        }
                    } finally {
                        ctx.fireChannelRead(message); // Forward the original actual packet exactly once, unchanged.
                    }
                }
            });
        });
        awaitNativeNetworkFuture("install passive observer on owned native Netty event loop", future);
        report.addProperty("nativeServerPacketObserver", "passive decoded ATTACK/STAB/CARRIED arrivals; original messages forwarded unchanged; native cached AD from existing server attack hooks");
    }

    private void awaitNativeNetworkFuture(String step, io.netty.util.concurrent.Future<?> future) {
        try {
            fixtures.awaitClient(step, client -> future.isDone());
            if (!future.isSuccess()) throw new AssertionError("Native network observation step failed: " + step, future.cause());
        } catch (RuntimeException | Error failure) {
            future.cancel(false);
            throw failure;
        }
    }

    private void removeSupportPacketObserver() {
        Channel channel = observedServerChannel;
        if (channel == null) return;
        if (channel.isOpen()) {
            var future = channel.eventLoop().submit(() -> {
                if (channel.pipeline().get(PACKET_OBSERVER) != null) channel.pipeline().remove(PACKET_OBSERVER);
            });
            awaitNativeNetworkFuture("remove only this fixture's passive native packet observer", future);
        }
        observedServerChannel = null;
        report.addProperty("nativePacketObserverRemoved", true);
    }

    private void drainSupportPackets() {
        BossQaIsolation.require(server.isSameThread(), "Copy native packet metadata only on the actual server thread");
        PacketArrival arrival;
        while ((arrival = packetQueue.poll()) != null) {
            if (nativePacketArrivals == null || !arrival.caseId().equals(packetCase)) continue;
            JsonObject sample = new JsonObject();
            sample.addProperty("sequence", arrival.sequence());
            sample.addProperty("arrivalNanoTime", arrival.nanoTime());
            sample.addProperty("kind", arrival.kind());
            sample.addProperty("requestedSlot", arrival.slot());
            sample.addProperty("drainedAtServerTick", server.getTickCount());
            // This is an END-tick state, NOT misrepresented as pre-packet attributes or a native damage hook.
            sample.add("serverStateAtDrain", serverState());
            nativePacketArrivals.add(sample);
        }
    }

    private void observeServer(MinecraftServer ticking) {
        if (ticking != server) return;
        drainSupportPackets();
        if (serverSamples != null && serverSamples.size() < 200) serverSamples.add(serverState());
        if (friendChannel != null) {
            Object packet;
            while ((packet = friendChannel.readOutbound()) != null) ReferenceCountUtil.release(packet);
        }
    }

    private JsonObject clientState(Minecraft client) {
        JsonObject out = new JsonObject();
        out.addProperty("tick", client.player.tickCount);
        out.addProperty("nanoTime", System.nanoTime());
        out.addProperty("selectedSlot", client.player.getInventory().getSelectedSlot());
        out.addProperty("mainHand", BuiltInRegistries.ITEM.getKey(client.player.getMainHandItem().getItem()).toString());
        out.addProperty("maceLease", HeldSlotManager.holds(mace));
        out.addProperty("autoMaceEnabled", mace.isEnabled());
        out.addProperty("autoMaceInfo", mace.info());
        out.addProperty("hostAimEnabled", hostAim.isEnabled());
        out.addProperty("bridgeAvailable", NativeAimBridge.available());
        out.addProperty("scopedSmoothOverrideOutsideCall", NativeAimBridge.override(hostAim, "smooth-mode"));
        out.addProperty("hostSavedSmooth", hostAim.value("smooth-mode"));
        out.addProperty("windowActive", client.isWindowActive());
        out.addProperty("glfwFocused", GLFW.glfwGetWindowAttrib(client.getWindow().handle(), GLFW.GLFW_FOCUSED));
        out.addProperty("mouseGrabbed", client.mouseHandler.isMouseGrabbed());
        out.addProperty("nativeMouseLookAvailable", (Boolean) invokePublicNative(mouseGuard));
        out.addProperty("cameraIsPlayer", client.getCameraEntity() == client.player);
        out.addProperty("paused", client.isPaused());
        out.addProperty("screen", client.gui.screen() == null ? "none" : client.gui.screen().getClass().getName());
        out.addProperty("overlay", client.gui.overlay() == null ? "none" : client.gui.overlay().getClass().getName());
        out.addProperty("attackKeyDown", client.options.keyAttack.isDown());
        out.addProperty("physicalLeftButton", GLFW.glfwGetMouseButton(client.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT));
        out.addProperty("yaw", client.player.getYRot());
        out.addProperty("pitch", client.player.getXRot());
        out.addProperty("x", client.player.getX()); out.addProperty("y", client.player.getY()); out.addProperty("z", client.player.getZ());
        out.addProperty("onGround", client.player.onGround());
        out.addProperty("fallDistance", client.player.fallDistance);
        out.addProperty("velocityY", client.player.getDeltaMovement().y);
        out.addProperty("attackSpeed", client.player.getAttributeValue(Attributes.ATTACK_SPEED));
        out.addProperty("attackDamage", client.player.getAttributeValue(Attributes.ATTACK_DAMAGE));
        out.addProperty("attackStrength", client.player.getAttackStrengthScale(0));
        out.addProperty("food", client.player.getFoodData().getFoodLevel());
        Player target = client.level.getPlayerByUUID(targetId);
        out.addProperty("targetDistance", target == null ? -1 : target.distanceTo(client.player));
        out.addProperty("targetProtected", target != null && PvpUtil.isProtectedPlayer(client, target));
        out.addProperty("nativeAntiBotEnabled", nativeModule("antibot") != null && nativeModule("antibot").isEnabled());
        out.addProperty("nativeAntiBotSuppressTarget", target != null && (Boolean) invokePublicNative(antiBotGuard, target));
        out.addProperty("nativeTeamsEnabled", teams.enabled);
        out.addProperty("nativeTeamsFriends", teams.settings.get("friends"));
        out.addProperty("lookIntersectsTarget", target != null && rayHits(client, target, 6.0));
        out.addProperty("maceRangeLegal", target != null && client.player.isWithinAttackRange(client.player.getInventory().getItem(4), target.getBoundingBox(), 0));
        out.addProperty("nativeCrosshairTarget", client.hitResult instanceof EntityHitResult hit && hit.getEntity() == target);
        out.addProperty("targetUsingItem", target != null && target.isUsingItem());
        out.addProperty("targetBlocking", target != null && target.isBlocking());
        if (target != null) addTargetStackTelemetry(out, target);
        out.addProperty("heldNativeRange", client.player.getAttackRangeWith(client.player.getMainHandItem()).effectiveMaxRange(client.player));
        return out;
    }

    private boolean rayHits(Minecraft client, Player target, double distance) {
        Vec3 eye = client.player.getEyePosition();
        return target.getBoundingBox().clip(eye, eye.add(client.player.getLookAngle().scale(distance))).isPresent();
    }

    private JsonObject serverState() {
        BossQaIsolation.require(server.isSameThread(), "AutoMace telemetry must remain on the real server thread");
        ServerPlayer local = local();
        JsonObject out = BossQaDamageObserver.snapshot(server, local, target());
        out.addProperty("velocityX", local.getDeltaMovement().x);
        out.addProperty("velocityY", local.getDeltaMovement().y);
        out.addProperty("velocityZ", local.getDeltaMovement().z);
        out.addProperty("horizontalVelocity", Math.hypot(local.getDeltaMovement().x, local.getDeltaMovement().z));
        out.addProperty("food", local.getFoodData().getFoodLevel());
        out.addProperty("saturation", local.getFoodData().getSaturationLevel());
        TagValueOutput food = TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
        local.getFoodData().addAdditionalSaveData(food);
        out.addProperty("foodExhaustion", food.buildResult().getFloatOr("foodExhaustionLevel", -1.0f));
        out.addProperty("spear6Damage", local.getInventory().getItem(6).getDamageValue());
        out.addProperty("spear7Damage", local.getInventory().getItem(7).getDamageValue());
        out.addProperty("spear6HasPiercingComponent", local.getInventory().getItem(6).has(DataComponents.PIERCING_WEAPON));
        out.addProperty("serverYaw", local.getYRot());
        out.addProperty("serverPitch", local.getXRot());
        out.addProperty("axe5Damage", local.getInventory().getItem(5).getDamageValue());
        out.addProperty("mace4Damage", local.getInventory().getItem(4).getDamageValue());
        out.addProperty("heldNativeRange", local.getAttackRangeWith(local.getMainHandItem()).effectiveMaxRange(local));
        out.addProperty("maceRangeLegal", local.isWithinAttackRange(local.getInventory().getItem(4), target().getBoundingBox(), 0));
        out.addProperty("spearRangeLegal", local.isWithinAttackRange(local.getInventory().getItem(6), target().getBoundingBox(), 0));
        out.addProperty("targetUsingItem", target().isUsingItem());
        out.addProperty("targetBlocking", target().isBlocking());
        addTargetStackTelemetry(out, target());
        out.addProperty("targetShieldCooldown", target().getCooldowns().isOnCooldown(new ItemStack(Items.SHIELD)));
        out.addProperty("targetShieldCooldownPercent", target().getCooldowns().getCooldownPercent(new ItemStack(Items.SHIELD), 0));
        return out;
    }

    private static double horizontalTravel(JsonObject state, JsonObject origin) {
        return Math.hypot(state.get("attackerX").getAsDouble() - origin.get("attackerX").getAsDouble(),
                state.get("attackerZ").getAsDouble() - origin.get("attackerZ").getAsDouble());
    }

    private void addTargetStackTelemetry(JsonObject out, Player target) {
        ItemStack offhand = target.getOffhandItem();
        ItemStack using = target.getUseItem();
        out.addProperty("targetOffhand", BuiltInRegistries.ITEM.getKey(offhand.getItem()).toString());
        out.addProperty("targetOffhandCount", offhand.getCount());
        out.addProperty("targetOffhandStack", offhand.toString());
        out.addProperty("targetOffhandComponents", offhand.getComponents().toString());
        out.addProperty("targetOffhandHasBlocksAttacks", offhand.has(DataComponents.BLOCKS_ATTACKS));
        out.addProperty("targetUseItem", BuiltInRegistries.ITEM.getKey(using.getItem()).toString());
        out.addProperty("targetUseItemCount", using.getCount());
        out.addProperty("targetUseItemStack", using.toString());
        out.addProperty("targetUseItemComponents", using.getComponents().toString());
        out.addProperty("targetUseItemHasBlocksAttacks", using.has(DataComponents.BLOCKS_ATTACKS));
        out.addProperty("targetNativeUseTicks", target.getTicksUsingItem());
        out.addProperty("targetUsedItemHand", target.getUsedItemHand().name());
        out.addProperty("targetLivingFlagsUnsigned", Byte.toUnsignedInt(target.getEntityData().get(livingFlags)));
    }

    @SuppressWarnings("unchecked")
    private static net.minecraft.network.syncher.EntityDataAccessor<Byte> nativeLivingFlagsAccessor() {
        try {
            // Read ONLY Minecraft's real synched flags accessor for telemetry. Never set the data/flags.
            var field = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("DATA_LIVING_ENTITY_FLAGS");
            field.setAccessible(true);
            return (net.minecraft.network.syncher.EntityDataAccessor<Byte>) field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot read actual native living flags accessor for shield telemetry", failure);
        }
    }

    private static java.lang.reflect.Method publicNativeMethod(String owner, String name, Class<?>... parameters) {
        try {
            return Class.forName(owner).getMethod(name, parameters); // PUBLIC native API3 methods only.
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Actual API3 public telemetry method unavailable: " + owner + "." + name, failure);
        }
    }

    private static Object invokePublicNative(java.lang.reflect.Method method, Object... arguments) {
        try {
            return method.invoke(null, arguments);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Actual public native telemetry call failed: " + method, failure);
        }
    }

    private Module nativeModule(String id) { return (Module) invokePublicNative(hostLookup, id); }

    private JsonObject hostSnapshot() {
        JsonObject out = new JsonObject();
        out.addProperty("actualClass", hostAim.getClass().getName());
        out.addProperty("enabled", hostAim.isEnabled());
        JsonObject values = new JsonObject();
        hostAim.settings().forEach(setting -> values.addProperty(setting.id(), hostAim.value(setting.id())));
        out.add("resolvedSettings", values);
        out.add("storedSettings", new GsonBuilder().create().toJsonTree(AutismConfig.getGlobal().modules.get("aim-assist").settings));
        return out;
    }

    private JsonObject diskHost() {
        try {
            return JsonParser.parseString(Files.readString(BossQaIsolation.profile().resolve("config/autism/config.json")))
                    .getAsJsonObject().getAsJsonObject("modules").getAsJsonObject("aim-assist").deepCopy();
        } catch (IOException failure) { throw new AssertionError("Cannot snapshot isolated host AimAssist saved configuration", failure); }
    }

    private void assertHostUnchanged() {
        context.runOnClient(client -> {
            BossQaIsolation.require(!hostAim.isEnabled() && initialHost.equals(hostSnapshot()), "Borrowed aiming changed native host enablement/saved settings");
            for (var setting : hostAim.settings()) BossQaIsolation.require(NativeAimBridge.override(hostAim, setting.id()) == null
                    && NativeAimBridge.scopedValue("aim-assist", setting.id()) == null, "Lexical native aim setting leaked outside its call");
        });
        BossQaIsolation.require(initialDiskHost.equals(diskHost()), "Native AimAssist settings changed on disk");
    }

    private ServerPlayer local() {
        ServerPlayer player = server.getPlayerList().getPlayer(localId);
        BossQaIsolation.require(player != null, "Owned native local actor disappeared");
        return player;
    }

    private ServerPlayer target() {
        ServerPlayer player = server.getPlayerList().getPlayer(targetId);
        BossQaIsolation.require(player != null, "Owned native target disappeared");
        return player;
    }

    private Player visible(Minecraft client) {
        Player player = client.level.getPlayerByUUID(targetId);
        BossQaIsolation.require(player != null && player.isAlive(), "Native target is not alive/replicated");
        return player;
    }

    private void writeReport() {
        try {
            String name = "automace-support-native".equals(System.getProperty("boss.qa.batch"))
                    ? "automace-support-native-20261018.json" : "automace-native-20261018.json";
            Files.writeString(BossQaIsolation.profile().resolve(name), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        } catch (IOException failure) { throw new AssertionError("Cannot preserve actual AutoMace native evidence", failure); }
    }

    @Override public void close() {
        context.runOnClient(client -> {
            clientSamples = null;
            intervention = null;
            mace.setEnabled(false);
            HeldSlotManager.clear(BossPvpAddon.autoClutch);
            client.gui.setScreen(null);
            oldSettings.forEach(mace::setValueTransient);
            teams.enabled = oldTeamsEnabled;
            if (oldFriends == null) teams.settings.remove("friends"); else teams.settings.put("friends", oldFriends);
        });
        fixtures.onServer("close only this fixture's extra native friend and telemetry", () -> {
            serverSamples = null;
            packetCase = null; nativePacketArrivals = null; packetQueue.clear();
            if (servicedTargetConnection != null) {
                var connections = server.getConnection().getConnections();
                synchronized (connections) { connections.remove(servicedTargetConnection); }
                servicedTargetConnection = null;
                report.addProperty("ownedActorNativeConnectionServiceRemoved", true);
            }
            if (friend != null) {
                server.getPlayerList().remove(friend);
                friend = null;
            }
            if (friendConnection != null) friendConnection.disconnect(Component.literal("AutoMace QA fixture closed"));
            if (friendChannel != null) friendChannel.finishAndReleaseAll();
            friendChannel = null;
            return null;
        });
        removeSupportPacketObserver();
        fixtures.clearDamageObservation();
        report.add("hostAimFinal", context.computeOnClient(client -> hostSnapshot()));
        report.add("hostAimDiskFinal", diskHost());
        report.addProperty("hostAimSettingsUnchanged", initialHost.equals(report.get("hostAimFinal")) && initialDiskHost.equals(report.get("hostAimDiskFinal")));
        writeReport();
    }
}
