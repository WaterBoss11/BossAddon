package com.boss.pvp.client.mixin;

import com.boss.pvp.util.pvp.RotationManager;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LocalPlayer.class)
public abstract class AutoPotSilentRotationMixin {

    @Redirect(method = "sendPosition", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"))
    private void bosspvp$silentLookDown(ClientPacketListener connection, Packet<?> packet) {
        connection.send(RotationManager.spoofOutgoing(packet));
    }
}
