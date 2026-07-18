package com.boss.pvp.client.mixin;

import com.boss.pvp.flag.FlagReporter;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the flag reporter every client disconnect. A server-sent {@link ClientboundDisconnectPacket} is
 * flagged as a packet-kick first (with its own reason text); the generic {@code onDisconnect} then fires the
 * report and classifies against that flag — packet-kick if one just arrived, otherwise a plain kick /
 * connection loss. {@code require = 0} keeps a mapping miss from breaking the build.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class FlagDisconnectMixin {

    @Inject(method = "handleDisconnect", at = @At("HEAD"), require = 0)
    private void bosspvp$onPacketKick(ClientboundDisconnectPacket packet, CallbackInfo ci) {
        FlagReporter.markPacketKick(packet.reason());
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"), require = 0)
    private void bosspvp$onDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        FlagReporter.onDisconnect(details.reason());
    }
}
