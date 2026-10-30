package com.boss.pvp.test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;

import java.util.UUID;

/** Read-only, server-thread-only observations at actual native packet/attack dispatch. */
public final class BossQaDamageObserver {
    private static volatile Probe active;

    private static final class Probe {
        final MinecraftServer server;
        final UUID attackerId;
        final UUID targetId;
        final int targetEntityId;
        final JsonObject data = new JsonObject();
        final JsonArray events = new JsonArray();
        int sequence;
        int packetEntries;
        int attackEntries;
        boolean returned;
        Integer swordSwitchTick;

        Probe(MinecraftServer server, ServerPlayer attacker, ServerPlayer target, String id) {
            this.server = server;
            attackerId = attacker.getUUID();
            targetId = target.getUUID();
            targetEntityId = target.getId();
            data.addProperty("controlId", id);
            data.add("events", events);
            data.add("armedState", snapshot(server, attacker, target));
        }
    }

    private BossQaDamageObserver() { }

    static void arm(MinecraftServer server, ServerPlayer attacker, ServerPlayer target, String id) {
        BossQaIsolation.require(server.isSameThread() && !server.isPublished(), "Damage probe requires the owned unpublished server thread");
        BossQaIsolation.require(active == null, "Previous damage probe was not released");
        active = new Probe(server, attacker, target, id);
    }

    private static Probe matching(ServerPlayer attacker) {
        Probe p = active;
        if (p == null || !p.server.isSameThread()) return null;
        return p.attackerId.equals(attacker.getUUID()) && attacker.level().getServer() == p.server ? p : null;
    }

    private static JsonObject event(Probe p, ServerPlayer attacker, String kind) {
        ServerPlayer target = p.server.getPlayerList().getPlayer(p.targetId);
        BossQaIsolation.require(target != null && target.getId() == p.targetEntityId, "Observed native target left the owned server");
        JsonObject e = snapshot(p.server, attacker, target);
        e.addProperty("sequence", ++p.sequence);
        e.addProperty("kind", kind);
        e.addProperty("nanoTime", System.nanoTime());
        p.events.add(e);
        return e;
    }

    public static void packetHead(ServerPlayer attacker, int targetEntityId) {
        Probe p = matching(attacker);
        if (p == null || p.targetEntityId != targetEntityId) return;
        p.packetEntries++;
        p.data.add("packetHead", event(p, attacker, "attack-packet-head"));
    }

    public static void packetReturn(ServerPlayer attacker, int targetEntityId) {
        Probe p = matching(attacker);
        if (p == null || p.targetEntityId != targetEntityId) return;
        p.data.add("packetReturn", event(p, attacker, "attack-packet-return"));
        p.returned = true;
    }

    public static void attackHead(ServerPlayer attacker, Entity target) {
        Probe p = matching(attacker);
        if (p == null || !p.targetId.equals(target.getUUID())) return;
        p.attackEntries++;
        p.data.add("playerAttackHead", event(p, attacker, "player-attack-head"));
    }

    public static void attackReturn(ServerPlayer attacker, Entity target) {
        Probe p = matching(attacker);
        if (p == null || !p.targetId.equals(target.getUUID())) return;
        p.data.add("playerAttackReturn", event(p, attacker, "player-attack-return"));
    }

    public static void carriedReturn(ServerPlayer attacker, int requestedSlot) {
        Probe p = matching(attacker);
        if (p == null) return;
        JsonObject e = event(p, attacker, "carried-slot-return");
        e.addProperty("requestedSlot", requestedSlot);
        if (requestedSlot == 5 && p.swordSwitchTick == null) p.swordSwitchTick = p.server.getTickCount();
    }

    static boolean complete() { return active != null && active.returned; }

    static boolean waitedSinceSwordSwitch(int ticks) {
        Probe p = active;
        return p != null && p.swordSwitchTick != null && p.server.getTickCount() - p.swordSwitchTick >= ticks;
    }

    static JsonObject finish() {
        Probe p = active;
        BossQaIsolation.require(p != null && p.server.isSameThread(), "Damage probe finish escaped server thread");
        p.data.addProperty("packetEntries", p.packetEntries);
        p.data.addProperty("playerAttackEntries", p.attackEntries);
        p.data.addProperty("packetReturned", p.returned);
        if (p.swordSwitchTick != null) p.data.addProperty("firstSwordSwitchServerTick", p.swordSwitchTick);
        JsonObject copy = p.data.deepCopy();
        active = null;
        return copy;
    }

    static void disarm() { active = null; }

    static JsonObject snapshot(MinecraftServer server, ServerPlayer attacker, ServerPlayer target) {
        BossQaIsolation.require(server.isSameThread(), "Never read native damage/equipment state off the server thread");
        JsonObject out = new JsonObject();
        out.addProperty("serverTick", server.getTickCount());
        out.addProperty("worldGameTime", attacker.level().getGameTime());
        out.addProperty("tickRateFrozen", server.tickRateManager().isFrozen());
        out.addProperty("tickRateRunsNormally", server.tickRateManager().runsNormally());
        out.addProperty("attackerTick", attacker.tickCount);
        out.addProperty("attackerClientLoaded", attacker.connection.hasClientLoaded());
        out.addProperty("serverSelectedSlot", attacker.getInventory().getSelectedSlot());
        out.addProperty("serverMainHand", BuiltInRegistries.ITEM.getKey(attacker.getMainHandItem().getItem()).toString());
        out.addProperty("serverMainHandCount", attacker.getMainHandItem().getCount());
        out.addProperty("attackDamage", attacker.getAttributeValue(Attributes.ATTACK_DAMAGE));
        out.addProperty("attackDamageBase", attacker.getAttributeBaseValue(Attributes.ATTACK_DAMAGE));
        out.addProperty("attackSpeed", attacker.getAttributeValue(Attributes.ATTACK_SPEED));
        out.addProperty("attackSpeedBase", attacker.getAttributeBaseValue(Attributes.ATTACK_SPEED));
        out.addProperty("attackStrengthScale0", attacker.getAttackStrengthScale(0));
        out.addProperty("attackStrengthScaleHalf", attacker.getAttackStrengthScale(0.5f));
        out.addProperty("currentItemAttackStrengthDelay", attacker.getCurrentItemAttackStrengthDelay());
        out.addProperty("attackerX", attacker.getX());
        out.addProperty("attackerY", attacker.getY());
        out.addProperty("attackerZ", attacker.getZ());
        out.addProperty("attackerOnGround", attacker.onGround());
        out.addProperty("attackerFallDistance", attacker.fallDistance);
        out.addProperty("attackerUsingItem", attacker.isUsingItem());
        out.addProperty("attackerCreative", attacker.isCreative());
        out.addProperty("targetTick", target.tickCount);
        out.addProperty("targetClientLoaded", target.connection.hasClientLoaded());
        out.addProperty("targetHealth", target.getHealth());
        out.addProperty("targetAbsorption", target.getAbsorptionAmount());
        out.addProperty("targetArmor", target.getArmorValue());
        out.addProperty("targetInvulnerableTime", target.invulnerableTime);
        JsonArray modifiers = new JsonArray();
        attacker.getMainHandItem().forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            JsonObject m = new JsonObject();
            m.addProperty("attribute", attribute.equals(Attributes.ATTACK_DAMAGE) ? "ATTACK_DAMAGE"
                    : attribute.equals(Attributes.ATTACK_SPEED) ? "ATTACK_SPEED" : attribute.toString());
            m.addProperty("amount", modifier.amount());
            m.addProperty("operation", modifier.operation().name());
            modifiers.add(m);
        });
        out.add("declaredMainHandModifiers", modifiers);
        return out;
    }
}
