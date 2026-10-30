package com.boss.pvp.test.mixin;

import com.boss.pvp.test.BossQaDamageObserver;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-mod-only read-only observer; network-thread invocations are rejected before world reads. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class BossQaDamagePacketObserverMixin {
    @Inject(method = "handleAttack", at = @At("HEAD"), require = 1)
    private void bossQa$attackHead(ServerboundAttackPacket packet, CallbackInfo ci) {
        BossQaDamageObserver.packetHead(((ServerGamePacketListenerImpl) (Object) this).player, packet.entityId());
    }

    @Inject(method = "handleAttack", at = @At("RETURN"), require = 1)
    private void bossQa$attackReturn(ServerboundAttackPacket packet, CallbackInfo ci) {
        BossQaDamageObserver.packetReturn(((ServerGamePacketListenerImpl) (Object) this).player, packet.entityId());
    }

    @Inject(method = "handleSetCarriedItem", at = @At("RETURN"), require = 1)
    private void bossQa$carriedReturn(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        BossQaDamageObserver.carriedReturn(((ServerGamePacketListenerImpl) (Object) this).player, packet.getSlot());
    }
}
