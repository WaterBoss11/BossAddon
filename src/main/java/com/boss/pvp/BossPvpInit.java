package com.boss.pvp;

import com.boss.pvp.command.BossAutoTestCommand;
import com.boss.pvp.net.AutoTestPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

public final class BossPvpInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("bossautotest")
                .executes(ctx -> BossAutoTestCommand.trigger())));

        // Server-side test harness can't enable client modules itself, so it sends this trigger packet;
        // on receipt we run the same sequence as /bossautotest (enable modules, then start the suite).
        PayloadTypeRegistry.clientboundPlay().register(AutoTestPayload.TYPE, AutoTestPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(AutoTestPayload.TYPE,
            (payload, context) -> context.client().execute(BossAutoTestCommand::trigger));
    }
}
