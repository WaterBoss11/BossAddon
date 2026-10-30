package com.boss.pvp.module.combat;

import autismclient.api.module.DoubleSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.PvpUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;

/** Bounded, threat-only adaptation of ThunderHack's GPL-3.0 AntiFireBall behavior. */
public final class AntiFireballModule extends Module {
    private long lastAttackTick = Long.MIN_VALUE;

    public AntiFireballModule() {
        super(BossPvpAddon.ID + ":antifireball", "AntiFireball", "Deflects incoming vanilla fireballs within actual attack reach.");
        add(new DoubleSetting("range", "Search range", 4.0, 1.0, 6.0, 0.1));
        add(new IntSetting("delay", "Ticks between attempts", 4, 1, 20, 1));
    }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null
                || !mc.player.isAlive() || mc.player.isUsingItem() || mc.player.isSpectator() || PvpUtil.hostEnabled("hide")) return;
        long now = mc.level.getGameTime();
        if (lastAttackTick != Long.MIN_VALUE && now >= lastAttackTick && now - lastAttackTick < integer("delay")) return;
        double range = Math.min(decimal("range"), mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE));
        double bestDistance = range * range;
        LargeFireball best = null;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LargeFireball fireball) || fireball.isRemoved()) continue;
            if (fireball.getOwner() == mc.player || fireball.getOwner() instanceof Player owner && PvpUtil.isProtectedPlayer(mc, owner)) continue;
            double distance = PvpUtil.distSqToBox(mc.player.getEyePosition(), fireball.getBoundingBox());
            if (distance > bestDistance || !RangedCombatDecisions.incoming(
                    mc.player.getBoundingBox().getCenter().subtract(fireball.position()), fireball.getDeltaMovement(), 12.0, 1.25)) continue;
            if (!PvpUtil.canSee(mc, mc.player, mc.player.getEyePosition(), fireball.getBoundingBox().getCenter())) continue;
            best = fireball;
            bestDistance = distance;
        }
        if (best == null || !HeldSlotManager.tryAcquire(this, 86)) return;
        try {
            PvpUtil.ghostSafeAttack(mc, mc.player, best, best.getBoundingBox().getCenter(), true);
            lastAttackTick = now;
        } finally {
            HeldSlotManager.clear(this);
        }
    }

    @Override public void onDisable() { lastAttackTick = Long.MIN_VALUE; HeldSlotManager.clear(this); }
    @Override public void onGameLeft() { onDisable(); }
    @Override public void onGameJoin() { onDisable(); }
}
