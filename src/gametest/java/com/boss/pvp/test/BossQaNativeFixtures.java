package com.boss.pvp.test;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Isolated integrated-server fixtures. Entity creation, loadouts, damage and block evidence live on the
 * SERVER. A native PlayerList player plus EmbeddedChannel is the same connection mechanism used by
 * Minecraft 26.2 GameTestHelper.makeMockServerPlayerInLevel; it is not a client-added RemotePlayer.
 * The passive target is NOT evidence for a second human client's network latency or gliding physics.
 */
@SuppressWarnings("UnstableApiUsage")
final class BossQaNativeFixtures implements AutoCloseable {
    static final int FLOOR_Y = 80;
    static final Vec3 LOCAL_START = new Vec3(0.5, 81, -3.5);
    static final Vec3 TARGET_START = new Vec3(0.5, 81, -1);
    private static final long STEP_NANOS = TimeUnit.SECONDS.toNanos(10);
    private final ClientGameTestContext context;
    private final MinecraftServer server;
    private final UUID localId;
    private ServerPlayer target;
    private Connection targetConnection;
    private EmbeddedChannel targetChannel;
    private boolean closed;

    BossQaNativeFixtures(ClientGameTestContext context) {
        this.context = context;
        BossQaIsolation.profile();
        server = context.computeOnClient(Minecraft::getSingleplayerServer);
        localId = context.computeOnClient(client -> client.player == null ? null : client.player.getUUID());
        BossQaIsolation.require(server != null && localId != null && !server.isPublished(),
                "Fixtures require this test's unpublished integrated server");
    }

    void buildArena() {
        onServer("build fresh arena", () -> {
            ServerLevel level = server.overworld();
            for (int x = -12; x <= 12; x++) {
                for (int z = -12; z <= 12; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, FLOOR_Y, z), Blocks.BEDROCK.defaultBlockState());
                    for (int y = 81; y <= 91; y++) {
                        level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            // Native adjacent-face placement lane. A vanilla server must not be asked to accept air placement.
            for (int y = 81; y <= 90; y++) {
                level.setBlockAndUpdate(new BlockPos(1, y, 0), Blocks.STONE.defaultBlockState());
            }
            // One-block resistant hole: feet at (-6.5, 80, 0.5), three clear blocks overhead.
            BlockPos hole = new BlockPos(-7, 80, 0);
            level.setBlockAndUpdate(hole, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(hole.below(), Blocks.OBSIDIAN.defaultBlockState());
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                level.setBlockAndUpdate(hole.relative(direction), Blocks.OBSIDIAN.defaultBlockState());
            }
            // Backed ladder, normal vanilla climbing, with free headroom.
            for (int y = 81; y <= 88; y++) {
                level.setBlockAndUpdate(new BlockPos(6, y, -5), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(6, y, -6),
                        Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
            }
            // Source-water surface at y=81, one block deep, hard floor. No lava/immunity assumption.
            for (int x = -10; x <= -6; x++) {
                for (int z = 6; z <= 10; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, 79, z), Blocks.BEDROCK.defaultBlockState());
                    level.setBlockAndUpdate(new BlockPos(x, 80, z), Blocks.WATER.defaultBlockState());
                }
            }
            level.setBlockAndUpdate(new BlockPos(5, 81, 7), Blocks.COBWEB.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(5, 82, 7), Blocks.COBWEB.defaultBlockState());
            // One-block ledge for bounded ReverseStep; landing remains in the same safe arena.
            level.setBlockAndUpdate(new BlockPos(6, 81, 2), Blocks.STONE.defaultBlockState());
            ServerPlayer local = localPlayer();
            local.setGameMode(GameType.SURVIVAL);
            local.setHealth(local.getMaxHealth());
            local.getFoodData().setFoodLevel(20);
            local.teleportTo(LOCAL_START.x, LOCAL_START.y, LOCAL_START.z);
            return null;
        });
        awaitClient("arena chunks and local teleport", client -> client.level != null && client.player != null
                && client.level.getBlockState(new BlockPos(0, 80, -4)).is(Blocks.BEDROCK)
                && client.level.getBlockState(new BlockPos(1, 87, 0)).is(Blocks.STONE)
                && client.player.position().distanceToSqr(LOCAL_START) < 0.15);
        context.waitTicks(3);
    }

    UUID createPassiveTarget() {
        UUID id = onServer("register native survival target", () -> {
            BossQaIsolation.require(target == null, "Only one native QA target is allowed per fixture");
            UUID uuid = UUID.nameUUIDFromBytes(("boss-qa-target:" + BossQaIsolation.profile().getFileName())
                    .getBytes(StandardCharsets.UTF_8));
            BossQaIsolation.require(server.getPlayerList().getPlayer(uuid) == null, "Native QA target UUID already present");
            GameProfile profile = new GameProfile(uuid, "BossQaTarget");
            CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
            target = new ServerPlayer(server, server.overworld(), profile, cookie.clientInformation());
            targetConnection = new Connection(PacketFlow.SERVERBOUND);
            targetChannel = new EmbeddedChannel(targetConnection);
            server.getPlayerList().placeNewPlayer(targetConnection, target, cookie);
            target.setGameMode(GameType.SURVIVAL);
            target.setHealth(target.getMaxHealth());
            // Finish the native mock connection's load handshake; unloaded players are genuinely invulnerable.
            target.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
            BossQaIsolation.require(target.connection.hasClientLoaded()
                    && !target.isInvulnerableTo(server.overworld(), server.overworld().damageSources().playerAttack(localPlayer())),
                    "Native survival target has not completed its load handshake");
            target.teleportTo(TARGET_START.x, TARGET_START.y, TARGET_START.z);
            target.getInventory().clearContent();
            target.inventoryMenu.sendAllDataToRemote();
            BossQaIsolation.require(server.getPlayerList().getPlayer(uuid) == target
                    && server.overworld().getEntity(uuid) == target, "Native target is not registered on both server surfaces");
            return uuid;
        });
        awaitClient("native target replicated to the client", client -> client.level != null
                && client.level.getPlayerByUUID(id) != null && client.level.getPlayerByUUID(id).isAlive()
                && client.level.getPlayerByUUID(id).position().distanceToSqr(TARGET_START) < 0.15);
        context.waitTicks(3);
        return id;
    }

    void proveNativeTargetDamage(UUID targetId) {
        float before = onServer("read target health before real packet", () -> requireTarget(targetId).getHealth());
        context.runOnClient(client -> {
            Player visible = client.level.getPlayerByUUID(targetId);
            BossQaIsolation.require(visible != null && client.player.distanceTo(visible) < 3,
                    "Native fixture target is not in actual interaction range");
            client.gameMode.attack(client.player, visible);
            client.player.swing(InteractionHand.MAIN_HAND);
        });
        awaitServer("server-confirmed fixture attack", () -> requireTarget(targetId).getHealth() < before);
        float after = onServer("read target health after real packet", () -> requireTarget(targetId).getHealth());
        BossQaIsolation.require(after > 0 && after < before, "Fixture attack did not produce bounded real server damage");
        System.out.println("[BOSS-QA] fixture native-player damage: before=" + before + " after=" + after);
    }

    float targetHealth(UUID id) {
        return onServer("read authoritative target health", () -> requireTarget(id).getHealth());
    }

    com.google.gson.JsonObject damageState(UUID id) {
        return onServer("read native damage/tick/equipment state", () ->
                BossQaDamageObserver.snapshot(server, localPlayer(), requireTarget(id)));
    }

    void waitServerTicks(UUID id, int ticks) {
        int start = damageState(id).get("serverTick").getAsInt();
        awaitServer("allow " + ticks + " genuine server ticks", () -> server.getTickCount() - start >= ticks);
    }

    void beginDamageObservation(UUID id, String control) {
        onServer("arm read-only native damage observation", () -> {
            BossQaDamageObserver.arm(server, localPlayer(), requireTarget(id), control);
            return null;
        });
    }

    void waitDamageObservation() {
        awaitServer("actual server attack packet returned", BossQaDamageObserver::complete);
    }

    void waitSwordSwitchTicks(int ticks) {
        awaitServer("native carried slot plus " + ticks + " server ticks", () -> BossQaDamageObserver.waitedSinceSwordSwitch(ticks));
    }

    void scheduleAfterSwordSwitchTicks(int ticks, Runnable actualClientSend) {
        awaitServer("schedule actual client send after " + ticks + " native server ticks", () -> {
            if (!BossQaDamageObserver.waitedSinceSwordSwitch(ticks)) return false;
            Minecraft.getInstance().execute(actualClientSend); // Handoff only; never send/read client state on server thread.
            return true;
        });
    }

    void awaitHeldDiamondReady(int slot) {
        awaitServer("real held-diamond attributes and native full charge", () -> {
            ServerPlayer local = localPlayer();
            ItemStack held = local.getMainHandItem();
            if (local.getInventory().getSelectedSlot() != slot || !held.is(net.minecraft.world.item.Items.DIAMOND_SWORD)
                    || !local.connection.hasClientLoaded() || server.tickRateManager().isFrozen()) return false;
            double[] expected = {local.getAttributeBaseValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE),
                    local.getAttributeBaseValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED)};
            boolean[] supported = {true};
            held.forEachModifier(net.minecraft.world.entity.EquipmentSlot.MAINHAND, (attribute, modifier) -> {
                int index = attribute.equals(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) ? 0
                        : attribute.equals(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED) ? 1 : -1;
                if (index < 0) return;
                if (modifier.operation() != net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE) supported[0] = false;
                else expected[index] += modifier.amount(); // Read expected native metadata; never apply/update an attribute.
            });
            return supported[0] && expected[0] > 1.0
                    && Math.abs(local.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) - expected[0]) < 1.0e-7
                    && Math.abs(local.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED) - expected[1]) < 1.0e-7
                    && local.getAttackStrengthScale(0.5f) >= 1.0f;
        });
    }

    com.google.gson.JsonObject finishDamageObservation() {
        return onServer("copy/release read-only native damage observation", BossQaDamageObserver::finish);
    }

    void clearDamageObservation() {
        onServer("release native damage observer", () -> { BossQaDamageObserver.disarm(); return null; });
    }

    void awaitTargetDamage(UUID id, float before) {
        awaitServer("raw packet damages the real server player", () -> requireTarget(id).getHealth() < before);
    }

    void resetCombatActors(UUID id) {
        onServer("reset only native combat fixture actors", () -> {
            ServerPlayer other = requireTarget(id);
            other.setHealth(other.getMaxHealth());
            other.teleportTo(TARGET_START.x, TARGET_START.y, TARGET_START.z);
            other.setDeltaMovement(Vec3.ZERO);
            ServerPlayer local = localPlayer();
            local.setHealth(local.getMaxHealth());
            local.teleportTo(LOCAL_START.x, LOCAL_START.y, LOCAL_START.z);
            local.setDeltaMovement(Vec3.ZERO);
            return null;
        });
        awaitClient("combat actor reset replicated", client -> client.player.position().distanceToSqr(LOCAL_START) < 0.15
                && client.level.getPlayerByUUID(id) != null
                && client.level.getPlayerByUUID(id).position().distanceToSqr(TARGET_START) < 0.15);
        // Let native damage immunity and attack cooldown expire; do not manufacture either result.
        context.waitTicks(25);
    }

    private ServerPlayer requireTarget(UUID id) {
        ServerPlayer actual = server.getPlayerList().getPlayer(id);
        BossQaIsolation.require(actual != null && actual == target && actual.level() == server.overworld(), "Native QA target disappeared");
        return actual;
    }

    double localPositionY() {
        return onServer("read authoritative local y", () -> localPlayer().getY());
    }

    void startLadderTrial() {
        Vec3 start = new Vec3(6.5, 81, -5.5);
        onServer("begin owned backed-ladder trial", () -> {
            ServerPlayer local = localPlayer();
            local.setYRot(0);
            local.setXRot(0);
            local.teleportTo(start.x, start.y, start.z);
            local.setDeltaMovement(Vec3.ZERO);
            local.fallDistance = 0;
            return null;
        });
        awaitClient("native ladder trial position replicated", client -> client.player != null
                && client.player.position().distanceToSqr(start) < 0.15 && client.player.onClimbable());
        context.waitTicks(3);
    }

    void resetLocalToPlatform() {
        onServer("return owned local actor to platform", () -> {
            ServerPlayer local = localPlayer();
            local.teleportTo(LOCAL_START.x, LOCAL_START.y, LOCAL_START.z);
            local.setDeltaMovement(Vec3.ZERO);
            local.fallDistance = 0;
            return null;
        });
        awaitClient("local actor reset replicated", client -> client.player != null
                && client.player.position().distanceToSqr(LOCAL_START) < 0.15);
    }

    void setLocalLoadout(Map<Integer, ItemStack> items, int selected) {
        onServer("set isolated local loadout", () -> {
            ServerPlayer local = localPlayer();
            local.getInventory().clearContent();
            items.forEach((slot, stack) -> local.getInventory().setItem(slot, stack.copy()));
            local.inventoryMenu.sendAllDataToRemote();
            return null;
        });
        context.runOnClient(client -> {
            client.player.getInventory().setSelectedSlot(selected);
            client.getConnection().send(new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(selected));
        });
        awaitClient("loadout replicated", client -> items.entrySet().stream().allMatch(entry ->
                ItemStack.matches(client.player.getInventory().getItem(entry.getKey()), entry.getValue())));
        awaitServer("selected slot acknowledged", () -> localPlayer().getInventory().getSelectedSlot() == selected);
    }

    void startClutchFall() {
        onServer("begin adjacent-face clutch fall", () -> {
            ServerPlayer local = localPlayer();
            local.setHealth(local.getMaxHealth());
            local.fallDistance = 0;
            local.teleportTo(0.5, 88, 0.5);
            return null;
        });
    }

    /** Teleport only: gravity, fall distance, attributes and attack charge remain entirely native. */
    void startNaturalMaceFall() {
        onServer("begin one genuine short mace fall", () -> {
            localPlayer().teleportTo(0.5, 85, -3.0);
            return null;
        });
    }

    int catchBlocks() {
        return onServer("read server catch blocks", () -> {
            int count = 0;
            for (int y = 82; y <= 88; y++) {
                if (server.overworld().getBlockState(new BlockPos(0, y, 0)).is(Blocks.COBBLESTONE)) count++;
            }
            return count;
        });
    }

    int serverHotbarCount(int slot) {
        return onServer("read authoritative item count", () -> localPlayer().getInventory().getItem(slot).getCount());
    }

    void awaitSelectedSlot(int slot) {
        awaitServer("selected slot acknowledged", () -> localPlayer().getInventory().getSelectedSlot() == slot);
    }

    void awaitCatch() {
        awaitServer("server accepts a real clutch placement", () -> {
            for (int y = 82; y <= 88; y++) {
                if (server.overworld().getBlockState(new BlockPos(0, y, 0)).is(Blocks.COBBLESTONE)
                        && localPlayer().getInventory().getItem(4).getCount() < 64) return true;
            }
            return false;
        });
        awaitServer("native player lands on the placed catch", () -> localPlayer().onGround() && localPlayer().getY() > 82
                && server.overworld().getBlockState(BlockPos.containing(localPlayer().getX(),
                        localPlayer().getY() - 0.01, localPlayer().getZ())).is(Blocks.COBBLESTONE));
    }

    void clearCatchLaneAndReset() {
        onServer("reset only owned catch-lane blocks", () -> {
            for (int y = 81; y <= 89; y++) server.overworld().setBlockAndUpdate(new BlockPos(0, y, 0), Blocks.AIR.defaultBlockState());
            localPlayer().teleportTo(LOCAL_START.x, LOCAL_START.y, LOCAL_START.z);
            localPlayer().setHealth(localPlayer().getMaxHealth());
            localPlayer().fallDistance = 0;
            return null;
        });
        awaitClient("return to fixture platform", client -> client.player.position().distanceToSqr(LOCAL_START) < 0.15);
    }

    void awaitPlatformLanding() {
        awaitServer("disabled clutch reaches the real platform", () -> localPlayer().onGround() && Math.abs(localPlayer().getY() - 81) < 0.1);
    }

    private ServerPlayer localPlayer() {
        ServerPlayer local = server.getPlayerList().getPlayer(localId);
        BossQaIsolation.require(local != null && local.level() == server.overworld(), "QA local player left the fixture dimension");
        return local;
    }

    <T> T onServer(String step, Supplier<T> action) {
        BossQaIsolation.require(!closed, "Fixture is closed");
        CompletableFuture<T> future = server.submit(() -> {
            try {
                return action.get();
            } finally {
                drainTargetOutbound();
            }
        });
        try {
            awaitClient(step, client -> future.isDone());
            return future.join();
        } catch (CompletionException exception) {
            throw new AssertionError("Server fixture step failed: " + step, exception.getCause());
        } catch (RuntimeException | Error failure) {
            // Do not leave a queued mutation armed after an interaction deadline/assertion failure.
            future.cancel(false);
            throw failure;
        }
    }

    void awaitClient(String step, Predicate<Minecraft> condition) {
        long deadline = System.nanoTime() + STEP_NANOS;
        context.waitFor(client -> {
            BossQaIsolation.require(System.nanoTime() < deadline, "10-second interaction deadline exceeded: " + step);
            return condition.test(client);
        }, 200);
    }

    void awaitServer(String step, Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + STEP_NANOS;
        for (int ticks = 0; ticks < 200; ticks++) {
            BossQaIsolation.require(System.nanoTime() < deadline, "10-second interaction deadline exceeded: " + step);
            if (onServer(step, condition)) return;
            context.waitTick();
        }
        throw new AssertionError("200-tick interaction deadline exceeded: " + step);
    }

    private void drainTargetOutbound() {
        if (targetChannel == null) return;
        Object message;
        while ((message = targetChannel.readOutbound()) != null) ReferenceCountUtil.release(message);
    }

    @Override
    public void close() {
        if (closed) return;
        UUID removedId = onServer("remove owned native target", () -> {
            UUID id = target == null ? null : target.getUUID();
            try {
                if (target != null) {
                    server.getPlayerList().remove(target);
                    BossQaIsolation.require(server.getPlayerList().getPlayer(id) == null
                            && server.overworld().getEntity(id) == null, "Owned fixture player did not clean up on the server");
                }
            } finally {
                try {
                    if (targetConnection != null) targetConnection.disconnect(Component.literal("Boss QA fixture complete"));
                } finally {
                    if (targetChannel != null) targetChannel.finishAndReleaseAll();
                    target = null;
                    targetConnection = null;
                    targetChannel = null;
                }
            }
            return id;
        });
        if (removedId != null) {
            awaitClient("native target removal replicated", client -> client.level != null
                    && client.level.getPlayerByUUID(removedId) == null);
        }
        closed = true;
    }
}
