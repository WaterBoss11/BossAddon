package com.boss.pvp;

import com.boss.pvp.command.BossAutoTestCommand;
import com.boss.pvp.net.AutoTestPayload;
import com.boss.pvp.relay.RelayConfig;
import com.boss.pvp.relay.RelayManager;

import com.mojang.brigadier.arguments.StringArgumentType;

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

            // Chat relay control (closed pilot). Guaranteed send path independent of the ChatScreen mixin;
            // scope toggles, DMs, and status all live here. Registered always, but every action is inert
            // unless the pilot gate (relay.url + relay.invite) is configured.
            dispatcher.register(ClientCommands.literal("bossrelay")
                .then(ClientCommands.literal("off").executes(ctx -> setRelayMode(RelayManager.Mode.OFF)))
                .then(ClientCommands.literal("global").executes(ctx -> setRelayMode(RelayManager.Mode.GLOBAL)))
                .then(ClientCommands.literal("server").executes(ctx -> setRelayMode(RelayManager.Mode.SERVER)))
                .then(ClientCommands.literal("reconnect").executes(ctx -> relayReconnect()))
                .then(ClientCommands.literal("g")
                    .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> relaySend("global", null, StringArgumentType.getString(ctx, "message")))))
                .then(ClientCommands.literal("s")
                    .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                        .executes(ctx -> relaySend("server", null, StringArgumentType.getString(ctx, "message")))))
                .then(ClientCommands.literal("dm")
                    .then(ClientCommands.argument("user", StringArgumentType.word())
                        .then(ClientCommands.argument("message", StringArgumentType.greedyString())
                            .executes(ctx -> relaySend("dm", StringArgumentType.getString(ctx, "user"),
                                StringArgumentType.getString(ctx, "message"))))))
                .executes(ctx -> relayStatus()));
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

    // ---- /bossrelay ----------------------------------------------------------------------------------

    private static int setRelayMode(RelayManager.Mode mode) {
        if (relayGate()) return 1;
        RelayManager.get().setMode(mode);
        relayMsg("§b[Relay] scope = " + mode.name().toLowerCase()
            + (mode == RelayManager.Mode.OFF ? " (chat is normal)" : " (typed chat goes to relay)"));
        return 1;
    }

    private static int relaySend(String scope, String user, String message) {
        if (relayGate()) return 1;
        switch (scope) {
            case "server" -> RelayManager.get().sendServer(message);
            case "dm" -> RelayManager.get().sendDm(user, message);
            default -> RelayManager.get().sendGlobal(message);
        }
        return 1;
    }

    private static int relayReconnect() {
        if (relayGate()) return 1;
        relayMsg("§b[Relay] reconnecting…");
        RelayManager.get().connect();
        return 1;
    }

    private static int relayStatus() {
        if (relayGate()) return 1;
        RelayManager r = RelayManager.get();
        relayMsg("§b[Relay] status: §f" + r.status() + " §7| scope: §f" + r.mode().name().toLowerCase());
        return 1;
    }

    /** True (and prints a hint) when the relay isn't configured — the pilot gate. */
    private static boolean relayGate() {
        if (RelayConfig.isConfigured()) return false;
        relayMsg("§7[Relay] Not enabled on this install (closed pilot — no invite configured).");
        return true;
    }

    private static void relayMsg(String s) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(s));
        }
    }
}
