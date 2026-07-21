package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.misc.VelocityClampModule;
import com.boss.pvp.util.NumericSanity;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

/**
 * Packet crash guard, position side ({@link VelocityClampModule}, defensive, default on). Companion to
 * {@code VelocityMixin} (which clamps the local player's velocity packet): this drops any incoming packet whose
 * raw position or velocity is malformed (NaN/Infinity) or absurdly large — beyond
 * {@link NumericSanity#SANE_LIMIT}, well past the world border — before vanilla applies it and overflows its own
 * section/collision math and crashes.
 *
 * <p>Covered here: explosion knockback ({@link ClientboundExplodePacket}), the local player teleport
 * ({@link ClientboundPlayerPositionPacket}), entity teleports ({@link ClientboundTeleportEntityPacket}), entity
 * position syncs ({@link ClientboundEntityPositionSyncPacket}) and entity spawns ({@link ClientboundAddEntityPacket}).
 * All share the one {@link NumericSanity} check, so a new overflow vector is caught by the same mechanism rather
 * than needing another one-off fix.
 *
 * <p>Purely defensive and local: it only <em>drops</em> a malformed packet (the client keeps its current, valid
 * state) — it never edits anything sent to the server and never fabricates a teleport acknowledgement. The guard
 * only fires on values orders of magnitude past anything legitimate, so real gameplay is never touched. Injections
 * use {@code require = 0} so a future mapping change degrades to "not guarded" rather than a hard crash. Cancelling
 * at HEAD (before vanilla reschedules the packet to the client thread) simply means it is never processed.
 */
@Mixin(ClientPacketListener.class)
public abstract class PacketSanityMixin {

    @Inject(method = "handleExplosion", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$explosion(ClientboundExplodePacket packet, CallbackInfo ci) {
        VelocityClampModule guard = BossPvpAddon.velocityClamp;
        if (guard == null || !guard.active()) return;

        boolean bad = bosspvp$vecInsane(packet.center())
                || NumericSanity.isInsane(packet.radius(), NumericSanity.SANE_LIMIT);
        if (!bad) {
            Optional<Vec3> knockback = packet.playerKnockback();
            bad = knockback.isPresent() && bosspvp$vecInsane(knockback.get());
        }
        if (bad) {
            guard.onRejected("explosion", "center=" + packet.center() + " r=" + packet.radius()
                + " knockback=" + packet.playerKnockback().orElse(null));
            ci.cancel();
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$movePlayer(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if (bosspvp$rejectMove(packet.change(), "player teleport")) ci.cancel();
    }

    @Inject(method = "handleTeleportEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$teleportEntity(ClientboundTeleportEntityPacket packet, CallbackInfo ci) {
        if (bosspvp$rejectMove(packet.change(), "entity teleport")) ci.cancel();
    }

    @Inject(method = "handleEntityPositionSync", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$positionSync(ClientboundEntityPositionSyncPacket packet, CallbackInfo ci) {
        if (bosspvp$rejectMove(packet.values(), "entity position sync")) ci.cancel();
    }

    @Inject(method = "handleAddEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$addEntity(ClientboundAddEntityPacket packet, CallbackInfo ci) {
        VelocityClampModule guard = BossPvpAddon.velocityClamp;
        if (guard == null || !guard.active()) return;
        if (NumericSanity.isInsane(packet.getX(), packet.getY(), packet.getZ())) {
            guard.onRejected("entity spawn",
                "pos=(" + packet.getX() + ", " + packet.getY() + ", " + packet.getZ() + ")");
            ci.cancel();
        }
    }

    /** Shared drop decision for a {@link PositionMoveRotation}-carrying packet: true (and recorded) if its
     * position or delta is out of range. */
    @Unique
    private static boolean bosspvp$rejectMove(PositionMoveRotation change, String kind) {
        VelocityClampModule guard = BossPvpAddon.velocityClamp;
        if (guard == null || !guard.active() || change == null) return false;
        if (bosspvp$vecInsane(change.position()) || bosspvp$vecInsane(change.deltaMovement())) {
            guard.onRejected(kind, "pos=" + change.position() + " delta=" + change.deltaMovement());
            return true;
        }
        return false;
    }

    @Unique
    private static boolean bosspvp$vecInsane(Vec3 v) {
        return v != null && NumericSanity.isInsane(v.x, v.y, v.z);
    }
}
