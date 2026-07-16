package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;

import autismclient.modules.Module;
import autismclient.api.module.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.block.Blocks;

public final class CriticalsModule extends Module {

    private long lastCritMs = 0L;

    public CriticalsModule() {
        super(BossPvpAddon.ID + ":criticals", "Criticals", "Always crit: packet / mini-jump / jump / no-ground.");

        add(new ChoiceSetting("mode", "Mode", "Packet", "Packet", "MiniJump", "Jump", "NoGround").group("General"));
        add(new BoolSetting("onlyWithAura", "Only with aura active", false).group("General"));
        add(new BoolSetting("requireFullCharge", "Require full charge", true).group("General"));
        add(new BoolSetting("groundCheck", "Ground check", true).group("General"));
        add(new IntSetting("delay", "Min delay (ms)", 100, 0, 1000, 10).group("General"));
        add(new DoubleSetting("miniJumpHeight", "Mini-jump height", 0.1, 0.01, 0.42, 0.01).group("General"));
        add(new BoolSetting("stopSprint", "Stop sprint for crit", true)
            .description("Drop sprint just before the crit (vanilla blocks critical hits while sprinting).").group("General"));
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.getConnection() == null || mc.gui.screen() != null) return;

        // Never crit while actively breaking a block: the crit's jump/position packets make the server
        // think the player left the block face and reject the break (block rubberbands back). Left-click
        // is both attack and mine, so this guard is required regardless of the crit mode below.
        if (mc.gameMode != null && mc.gameMode.isDestroying()) return;

        boolean attacking = mc.options != null && mc.options.keyAttack.isDown();
        boolean auraActive = BossPvpAddon.killAura != null && BossPvpAddon.killAura.isEnabled()
            && BossPvpAddon.killAura.currentTarget() != null;
        if (bool("onlyWithAura")) { if (!auraActive) return; } else if (!attacking) return;

        if (!allowsCriticalHit(p)) return;
        if (bool("groundCheck") && !p.onGround()) return;
        if (bool("requireFullCharge") && !PvpUtil.fullCharge(p)) return;

        long now = System.currentTimeMillis();
        if (now - lastCritMs < PvpUtil.jitterMs(integer("delay"))) return;
        lastCritMs = now;

        if (bool("stopSprint") && p.isSprinting()) {
            mc.getConnection().send(new ServerboundPlayerCommandPacket(p, ServerboundPlayerCommandPacket.Action.STOP_SPRINTING));
            p.setSprinting(false);
        }

        double x = p.getX(), y = p.getY(), z = p.getZ();
        boolean horiz = p.horizontalCollision;
        switch (choice("mode")) {
            case "Packet" -> {

                mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y + 0.0625, z, false, horiz));
                mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, horiz));
                mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y + 1.1E-5, z, false, horiz));
                mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(x, y, z, false, horiz));
            }
            case "MiniJump" -> {
                if (p.onGround()) p.setDeltaMovement(p.getDeltaMovement().x, decimal("miniJumpHeight"), p.getDeltaMovement().z);
            }
            case "Jump" -> {
                if (p.onGround()) p.setDeltaMovement(p.getDeltaMovement().x, 0.42, p.getDeltaMovement().z);
            }
            case "NoGround" -> {
                mc.getConnection().send(new ServerboundMovePlayerPacket.StatusOnly(false, horiz));
            }
            default -> { }
        }
    }

    private boolean allowsCriticalHit(LocalPlayer p) {
        if (p.isInWater() || p.isInLava()) return false;
        if (p.onClimbable()) return false;
        if (p.isPassenger()) return false;
        if (p.isNoGravity()) return false;
        if (p.isUsingItem()) return false;
        if (p.getAbilities().flying) return false;
        if (p.hasEffect(MobEffects.LEVITATION) || p.hasEffect(MobEffects.SLOW_FALLING)
                || p.hasEffect(MobEffects.BLINDNESS)) return false;
        if (p.level().getBlockState(p.blockPosition()).is(Blocks.COBWEB)) return false;
        return true;
    }
}
