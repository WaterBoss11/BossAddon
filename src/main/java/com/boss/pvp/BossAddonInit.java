package com.boss.pvp;

import com.boss.pvp.util.AddonHalves;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/**
 * BossAddon halves control: {@code /bossaddon pvp on|off}, {@code /bossaddon utility on|off}, and bare
 * {@code /bossaddon} for status. Lives in its OWN client entrypoint (not BossPvpInit) so the halves
 * feature is self-contained; matches the existing {@code /bosspvp menu} command style.
 *
 * <p>Turning a half off makes its modules genuinely inert (force-disabled + tick dispatch skipped — see
 * {@link AddonHalves}); turning it back on restores exactly the modules that were enabled before.
 */
public final class BossAddonInit implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
            dispatcher.register(ClientCommands.literal("bossaddon")
                .then(half(AddonHalves.PVP))
                .then(half(AddonHalves.UTILITY))
                .executes(ctx -> { msg("[BossAddon] " + AddonHalves.status()); return 1; })));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> half(String name) {
        return ClientCommands.literal(name)
            .then(ClientCommands.literal("on").executes(ctx -> apply(name, true)))
            .then(ClientCommands.literal("off").executes(ctx -> apply(name, false)))
            .executes(ctx -> { msg("[BossAddon] " + AddonHalves.status()); return 1; });
    }

    private static int apply(String half, boolean on) {
        String result = AddonHalves.setHalf(half, on);
        msg("[BossAddon] " + (result == null ? "Unknown half: " + half : result));
        return 1;
    }

    private static void msg(String s) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(s));
        }
    }
}
