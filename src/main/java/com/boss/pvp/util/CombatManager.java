package com.boss.pvp.util;

import com.boss.pvp.util.pvp.PvpUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public final class CombatManager {
    private static int pauseTicks;
    private static LivingEntity target;
    private static long attackMs;
    private static long attackNanos;
    private static int ownTotemPops;

    private CombatManager() {}

    public static void pauseCombat(int ticks) {
        if (ticks > pauseTicks) pauseTicks = ticks;
    }

    public static boolean isCombatPaused() {
        return pauseTicks > 0;
    }

    public static boolean shouldCancelAttack(boolean active, boolean paused, boolean protectedTarget, boolean fireball) {
        return active && (protectedTarget || paused && !fireball);
    }

    public static void recordAttack(Entity entity) {
        if (entity == null) return;
        target = entity instanceof LivingEntity living ? living : null;
        attackMs = System.currentTimeMillis();
        attackNanos = System.nanoTime();
    }

    public static long lastAttackMs() {
        return attackMs;
    }

    public static LivingEntity currentTarget(Minecraft mc) {
        if (mc == null || mc.player == null || mc.level == null || target == null
                || !target.isAlive() || target.isRemoved() || target.level() != mc.level) return null;
        long age = System.nanoTime() - attackNanos;
        if (age < 0 || age > 1_000_000_000L || target == mc.player) return null;
        if (target instanceof Player player && PvpUtil.isProtectedPlayer(mc, player)) return null;
        return target;
    }

    public static void observePacket(Minecraft mc, Packet<?> packet) {
        if (mc.player != null && mc.level != null && packet instanceof ClientboundEntityEventPacket event
                && event.getEventId() == 35 && event.getEntity(mc.level) == mc.player) ownTotemPops++;
    }

    public static int totemPops() {
        return ownTotemPops;
    }

    public static void tick() {
        if (pauseTicks > 0) pauseTicks--;
    }

    public static void reset() {
        pauseTicks = 0;
        target = null;
        attackMs = 0L;
        attackNanos = 0L;
        ownTotemPops = 0;
    }
}
