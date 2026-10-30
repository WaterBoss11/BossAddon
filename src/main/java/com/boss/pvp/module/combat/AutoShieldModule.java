package com.boss.pvp.module.combat;

import autismclient.api.module.DoubleSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.InputUtil;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.PvpUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

/** ThunderHack AutoShield behavior adapted under GPL-3.0, with explicit input ownership. */
public final class AutoShieldModule extends Module implements HeldSlotManager.Revocable {
    private boolean ownsUse;
    private InteractionHand hand;

    public AutoShieldModule() {
        super(BossPvpAddon.ID + ":autoshield", "AutoShield", "Raises an equipped shield against nearby threats without moving offhand items.");
        add(new DoubleSetting("range", "Threat range", 4.0, 1.0, 8.0, 0.1));
    }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null
                || !mc.player.isAlive() || mc.player.isSpectator() || PvpUtil.hostEnabled("hide")) {
            stop(mc);
            return;
        }
        if (ownsUse && InputUtil.physicallyDown(mc, mc.options.keyUse)) {
            ownsUse = false;
            hand = null;
            HeldSlotManager.clear(this);
            return;
        }
        boolean threat = threatNearby(mc);
        if (ownsUse) {
            boolean stillShield = hand != null && mc.player.isUsingItem() && mc.player.getUsedItemHand() == hand
                && mc.player.getUseItem().is(Items.SHIELD) && !mc.player.getCooldowns().isOnCooldown(mc.player.getUseItem());
            if (!threat || !stillShield || !HeldSlotManager.holds(this)) { stop(mc); return; }
            HeldSlotManager.request(this, 84);
            mc.options.keyUse.setDown(true);
            return;
        }
        if (!threat || mc.player.isUsingItem() || mc.options.keyUse.isDown()) return;
        if (mc.player.getOffhandItem().is(Items.SHIELD)) hand = InteractionHand.OFF_HAND;
        else if (mc.player.getMainHandItem().is(Items.SHIELD)) hand = InteractionHand.MAIN_HAND;
        else return;
        if (mc.player.getCooldowns().isOnCooldown(mc.player.getItemInHand(hand))
                || !HeldSlotManager.tryAcquire(this, 84)) { hand = null; return; }
        if (mc.gameMode.useItem(mc.player, hand).consumesAction() && mc.player.isUsingItem()
                && mc.player.getUsedItemHand() == hand && mc.player.getUseItem().is(Items.SHIELD)) {
            ownsUse = true;
            mc.options.keyUse.setDown(true);
        } else {
            hand = null;
            HeldSlotManager.clear(this);
        }
    }

    private boolean threatNearby(Minecraft mc) {
        var player = mc.player;
        for (var enemy : mc.level.players()) {
            if (!enemy.isAlive() || PvpUtil.isProtectedPlayer(mc, enemy)
                    || player.distanceToSqr(enemy) > decimal("range") * decimal("range")) continue;
            var toUs = player.getBoundingBox().getCenter().subtract(enemy.getEyePosition()).normalize();
            var toEnemy = enemy.getBoundingBox().getCenter().subtract(player.getEyePosition()).normalize();
            if (enemy.getLookAngle().dot(toUs) > 0.8 && player.getLookAngle().dot(toEnemy) > 0.0
                    && PvpUtil.canSeeEntity(mc, player, enemy)) return true;
        }
        return false;
    }

    private void stop(Minecraft mc) {
        if (ownsUse && mc != null && mc.options != null) {
            boolean physical = InputUtil.physicallyDown(mc, mc.options.keyUse);
            mc.options.keyUse.setDown(physical);
            if (!physical && mc.player != null && mc.player.isUsingItem()
                    && mc.player.getUsedItemHand() == hand && mc.player.getUseItem().is(Items.SHIELD)) {
                if (mc.gameMode != null) mc.gameMode.releaseUsingItem(mc.player); else mc.player.stopUsingItem();
            }
        }
        ownsUse = false;
        hand = null;
        HeldSlotManager.clear(this);
    }

    @Override public void onLeaseRevoked() { stop(Minecraft.getInstance()); }

    @Override public void onDisable() { stop(Minecraft.getInstance()); }
    @Override public void onGameLeft() { onDisable(); }
    @Override public void onGameJoin() { onDisable(); }
}
