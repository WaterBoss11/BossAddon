package com.boss.utility.mixin;

import com.boss.utility.flag.FlagReporter;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds the flag reporter every client disconnect. A server-sent {@link ClientboundDisconnectPacket} is
 * flagged first (with its own reason text); the generic {@code onDisconnect} then fires the report. The
 * reporter classifies the event from whether that server reason just arrived AND whether we had reached the
 * in-world (play) phase — the play listener ({@link ClientPacketListener}) means in-world, the config listener
 * means still connecting — so a VPN/loader refusal reads as "Server rejected connection", a mid-game kick as
 * "Kicked", and a dropped link as "Timed out"/"Disconnected". {@code require = 0} keeps a mapping miss from
 * breaking the build.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class FlagDisconnectMixin {

    @Inject(method = "handleDisconnect", at = @At("HEAD"), require = 0)
    private void bossutility$onPacketKick(ClientboundDisconnectPacket packet, CallbackInfo ci) {
        FlagReporter.markPacketKick(packet.reason());
    }

    @Inject(method = "onDisconnect", at = @At("HEAD"), require = 0)
    private void bossutility$onDisconnect(DisconnectionDetails details, CallbackInfo ci) {
        // Play listener => we were in-world (kick); config listener => still connecting (rejection).
        boolean inWorld = (Object) this instanceof ClientPacketListener;
        FlagReporter.onDisconnect(details.reason(), inWorld);
    }
}
