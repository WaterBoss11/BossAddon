package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.combat.VelocityModule;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Intercepts incoming knockback/velocity packets for the local player and hands them to
 * {@link VelocityModule#onMotionPacket}. If that returns true, the packet is cancelled and
 * VelocityModule is responsible for applying whatever replacement motion it wants (either
 * immediately or on a later tick — see VelocityModule.tick()).
 */
@Mixin(ClientPacketListener.class)
public abstract class VelocityMixin {

    @Inject(method = "handleSetEntityMotion", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$velocity(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        VelocityModule m = BossPvpAddon.velocity;
        if (m == null || !m.isEnabled()) return;

        Minecraft mc = Minecraft.getInstance();
        // handleSetEntityMotion runs first on the network thread, then reschedules to the client thread via
        // PacketUtils.ensureRunningOnSameThread. Only act on the client thread: mutating player velocity off
        // the main thread is unsafe, and a non-cancelling mode would otherwise arm its state twice per packet.
        if (!mc.isSameThread()) return;

        LocalPlayer p = mc.player;
        // 26.2: ClientboundSetEntityMotionPacket is a record — getId()/getXa()/getYa()/getZa() are gone.
        // id() is the target entity id; movement() is the already-decoded motion Vec3 (the old getXa/Ya/Za
        // returned raw encoded shorts, so movement() is both the correct accessor and the real values
        // onMotionPacket expects).
        if (p == null || packet.id() != p.getId()) return;

        Vec3 mv = packet.movement();
        if (m.onMotionPacket(mv.x, mv.y, mv.z)) {
            ci.cancel();
        }
    }
}
