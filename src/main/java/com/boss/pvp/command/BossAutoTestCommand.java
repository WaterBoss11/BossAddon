package com.boss.pvp.command;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Automated test harness — <b>not</b> a user command. {@link #trigger()} enables the PvP test modules and, after
 * a short countdown driven by {@link #tickClient()}, runs the server-side {@code bosstest run} suite. It is
 * invoked only by the {@link com.boss.pvp.net.AutoTestPayload} receiver (server-driven test path) and the
 * per-tick hook in {@code BossPvpAddon}; there is no {@code /bossautotest} client command any more.
 */
public final class BossAutoTestCommand {

    private BossAutoTestCommand() {}

    private static final int SUCCESS = 1;
    private static int countdown = -1;

    /** Enable the test modules and arm the countdown that starts the suite. Returns a success code. */
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
