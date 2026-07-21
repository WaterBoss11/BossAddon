package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.combat.VelocityModule;
import com.boss.pvp.module.misc.VelocityClampModule;
import com.boss.pvp.util.pvp.VelocityMath;

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
 * Intercepts incoming knockback/velocity packets for the local player. Two independent concerns share this one
 * hook (so they can't fight over ordering on the same method):
 *
 * <ol>
 *   <li><b>Packet Crash Guard</b> ({@link VelocityClampModule}, defensive, default on): if the server sent a
 *       malformed/absurd motion (NaN/Infinity or a magnitude that overflows vanilla's own position/section math
 *       and crashes the client), replace it with a clamped sane value and drop the packet before vanilla — or
 *       the Anti-Knockback module — ever sees it. Purely local; nothing is sent to the server.</li>
 *   <li><b>Velocity / Anti-Knockback</b> ({@link VelocityModule#onMotionPacket}): if that returns true the
 *       packet is cancelled and VelocityModule applies its own replacement motion (now or on a later tick).</li>
 * </ol>
 */
@Mixin(ClientPacketListener.class)
public abstract class VelocityMixin {

    @Inject(method = "handleSetEntityMotion", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$velocity(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        // handleSetEntityMotion runs first on the network thread, then reschedules to the client thread via
        // PacketUtils.ensureRunningOnSameThread. Only act on the client thread: mutating player velocity off
        // the main thread is unsafe, and a non-cancelling mode would otherwise arm its state twice per packet.
        if (!mc.isSameThread()) return;

        LocalPlayer p = mc.player;
        // 26.2: ClientboundSetEntityMotionPacket is a record — id() is the target entity id; movement() is the
        // already-decoded motion Vec3 (the old getXa/Ya/Za returned raw encoded shorts).
        if (p == null || packet.id() != p.getId()) return;

        Vec3 mv = packet.movement();

        // 1) Crash guard: clamp a malformed/malicious value BEFORE vanilla collision or Anti-Knockback see it.
        // Only in-range finite values pass through untouched, so legitimate high-speed motion is never altered.
        VelocityClampModule guard = BossPvpAddon.velocityClamp;
        if (guard != null && guard.active()) {
            double cx = VelocityMath.clampMotion(mv.x);
            double cy = VelocityMath.clampMotion(mv.y);
            double cz = VelocityMath.clampMotion(mv.z);
            if (cx != mv.x || cy != mv.y || cz != mv.z) {   // NaN != anything is true, so NaN is caught too
                p.setDeltaMovement(cx, cy, cz);
                guard.onClamped(mv.x, mv.y, mv.z);
                ci.cancel();
                return;   // don't let the poisoned value reach the Anti-Knockback path either
            }
        }

        // 2) Velocity / Anti-Knockback module (unchanged behaviour).
        VelocityModule m = BossPvpAddon.velocity;
        if (m == null || !m.isEnabled()) return;
        if (m.onMotionPacket(mv.x, mv.y, mv.z)) {
            ci.cancel();
        }
    }
}
