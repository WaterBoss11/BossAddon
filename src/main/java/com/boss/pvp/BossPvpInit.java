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
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommands.literal("bossautotest")
                .executes(ctx -> BossAutoTestCommand.trigger()));

            // Simple/Advanced menu mode toggle (global; persisted in boss-pvp.properties, not a setting).
            dispatcher.register(ClientCommands.literal("bosspvp")
                .then(ClientCommands.literal("menu")
                    .then(ClientCommands.literal("simple").executes(ctx -> setMenuMode(false)))
                    .then(ClientCommands.literal("advanced").executes(ctx -> setMenuMode(true)))
                    .executes(ctx -> setMenuMode(!com.boss.pvp.util.MenuMode.advanced()))));
        });

        // Server-side test harness can't enable client modules itself, so it sends this trigger packet;
        // on receipt we run the same sequence as /bossautotest (enable modules, then start the suite).
        PayloadTypeRegistry.clientboundPlay().register(AutoTestPayload.TYPE, AutoTestPayload.CODEC);
        ClientPlayNetworking.registerGlobalReceiver(AutoTestPayload.TYPE,
            (payload, context) -> context.client().execute(BossAutoTestCommand::trigger));
    }

    /** Apply the Simple/Advanced menu mode and print a one-line confirmation. Returns a command success code. */
    private static int setMenuMode(boolean advanced) {
        com.boss.pvp.util.MenuMode.setAdvanced(advanced);
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(
                "[Boss's PVP] Menu mode: " + (advanced ? "Advanced (all settings shown)" : "Simple (essential settings only)")));
        }
        return 1;
    }
}
