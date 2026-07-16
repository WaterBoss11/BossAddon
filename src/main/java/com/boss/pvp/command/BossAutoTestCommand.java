package com.boss.pvp.command;

import com.boss.pvp.BossPvpAddon;

import autismclient.commands.AutismCommandSource;
import autismclient.commands.Command;
import autismclient.modules.Module;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

public final class BossAutoTestCommand extends Command {

    private static int countdown = -1;

    public BossAutoTestCommand() {
        super("bossautotest", "Enable the test modules and auto-start the Boss PVP test suite.");
    }

    @Override
    public void build(LiteralArgumentBuilder<AutismCommandSource> builder) {
        builder.executes(this::run);
    }

    @Override
    public int run(CommandContext<AutismCommandSource> ctx) {
        return trigger();
    }

    public static int trigger() {
        enableModules();
        msg("§a[BossPVP] Auto-test: modules enabled, starting in 5s...");
        countdown = 100;
        return SUCCESS;
    }

    private static void enableModules() {
        setOn(BossPvpAddon.killAura);
        setOn(BossPvpAddon.autoTotem);
        setOn(BossPvpAddon.autoArmor);
        setOn(BossPvpAddon.surround);
        setOn(BossPvpAddon.reach);
        setOn(BossPvpAddon.noSlowdown);
        setOn(BossPvpAddon.antiEntityPush);
        setOn(BossPvpAddon.scaffold);
    }

    private static void setOn(Module m) {
        if (m != null && !m.isEnabled()) m.setEnabled(true);
    }

    public static void tickClient() {
        if (countdown < 0) return;
        if (--countdown <= 0) {
            countdown = -1;
            sendRun();
        }
    }

    private static void sendRun() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null) {
            mc.player.connection.sendCommand("bosstest run");
        }
    }

    private static void msg(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal(s));
    }
}
