package com.boss.pvp;

import com.boss.pvp.command.BossAutoTestCommand;
import com.boss.pvp.net.AutoTestPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * Client networking init for the automated test harness: registers the {@link AutoTestPayload} receiver so the
 * server-side test harness can trigger the same client-side "enable test modules + run suite" sequence.
 *
 * <p>Registers NO player-facing commands — every user command lives under {@code ?bossaddon} (see
 * {@link BossAddonInit}). The former standalone {@code /bossautotest}, {@code /bosspvp}, {@code /bosschat} and
 * {@code /bossrelay} commands were removed; there are no aliases.
 */
public final class BossPvpInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // The server-side test harness can't enable client modules itself, so it sends this trigger packet; on
        // receipt we run the same sequence the test harness uses (enable modules, then start the suite).
        PayloadTypeRegistry.clientboundPlay().register(AutoTestPayload.TYPE, AutoTestPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(AutoTestPayload.TYPE,
            (payload, context) -> context.client().execute(BossAutoTestCommand::trigger));
    }
}
