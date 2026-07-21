package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.misc.ReconfigureGuardModule;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundStartConfigurationPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes the server forcing the client from PLAY back into the configuration phase:
 * {@code handleConfigurationStart} on the PLAY listener is a RE-configuration (the initial login-time
 * configuration is handled by a different listener class), so it fires 0 times in a normal session, once for a
 * legitimate reconfigure (e.g. a resource-pack reload), and repeatedly in a malicious loop.
 *
 * <p><b>Purely observational.</b> It is injected at HEAD and is NOT cancellable — it never cancels, alters, or
 * suppresses the transition, so the server's reconfigure request is always handled normally. It only feeds
 * {@link ReconfigureGuardModule}, which disconnects the local client if these repeat into a loop.
 * {@code require = 0} keeps a mapping miss from breaking the build.
 */
@Mixin(ClientPacketListener.class)
public abstract class ReconfigureLoopMixin {

    @Inject(method = "handleConfigurationStart", at = @At("HEAD"), require = 0)
    private void bosspvp$observeReconfigure(ClientboundStartConfigurationPacket packet, CallbackInfo ci) {
        ReconfigureGuardModule m = BossPvpAddon.reconfigureGuard;
        if (m == null || !m.active()) return;
        Connection conn = ((CommonListenerConnectionAccessor) (Object) this).bosspvp$connection();
        m.onReconfigure(conn);
    }
}
