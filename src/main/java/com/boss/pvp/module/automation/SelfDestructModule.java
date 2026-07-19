package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismBindUtil;
import autismclient.util.AutismInputGate;

import com.boss.pvp.util.MenuMode;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

public final class SelfDestructModule extends Module {

    private static final long CONFIRM_WINDOW_MS = 3000L;
    private static final long STEP_DELAY_MS = 200L;

    private boolean prevDown = false;
    private long armedUntil = 0L;

    public SelfDestructModule() {
        super(BossPvpAddon.ID + ":selfdestruct", "SelfDestruct",
            "Panic button: wipes logs and this mod.");
        add(new KeybindSetting("triggerKey", "Trigger key", -1)
            .description("Bind a key to trigger SelfDestruct (unbound by default — must be set manually).")
            .group("General"));
        add(new BoolSetting("confirm", "Ask for confirmation", true)
            .description("The key must be pressed twice within 3 seconds — prevents accidental triggers.").group("General"));
        add(new BoolSetting("clearLogs", "Clear logs", true)
            .description("Deletes Minecraft's log files and crash reports.").group("Steps").visibleWhen(MenuMode::advanced));
        add(new BoolSetting("deleteMod", "Delete mod", true)
            .description("Deletes this addon's jar file from your mods folder.").group("Steps").visibleWhen(MenuMode::advanced));
        add(new BoolSetting("clearRecycleBin", "Clear recycle bin", true)
            .description("Empties the Windows recycle bin (does nothing on other systems).").group("Steps").visibleWhen(MenuMode::advanced));
    }

    @Override
    public void onDisable() {
        prevDown = false;
        armedUntil = 0L;
    }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null) return;
        LocalPlayer p = mc.player;
        int key = parseKey(value("triggerKey"));
        if (key == -1 || !AutismInputGate.canRunAutismKeybinds()) { prevDown = false; return; }

        boolean down = AutismBindUtil.isBindPressed(mc, key);
        boolean rising = down && !prevDown;
        prevDown = down;
        if (!rising) return;

        long now = System.currentTimeMillis();
        if (bool("confirm")) {
            if (now <= armedUntil) {
                armedUntil = 0L;
                execute(mc, p);
            } else {
                armedUntil = now + CONFIRM_WINDOW_MS;
                if (p != null) p.sendSystemMessage(
                    Component.literal("[BossPVP] SelfDestruct armed — press again to confirm"));
            }
        } else {
            execute(mc, p);
        }
    }

    private void execute(Minecraft mc, LocalPlayer p) {
        Path gameDir = FabricLoader.getInstance().getGameDir();

        if (bool("clearLogs")) {
            clearDir(gameDir.resolve("logs").toFile(), true);
            clearDir(gameDir.resolve("crash-reports").toFile(), false);
            sleep(STEP_DELAY_MS);
        }
        if (bool("deleteMod")) {
            deleteAddon(gameDir.resolve("mods").toFile());
            sleep(STEP_DELAY_MS);
        }
        if (bool("clearRecycleBin")) {
            clearRecycleBin();
        }
    }

    private void clearDir(File dir, boolean logsOnly) {
        try {
            if (dir == null || !dir.isDirectory()) return;
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (!f.isFile()) continue;
                if (logsOnly) {
                    String name = f.getName().toLowerCase(Locale.ROOT);
                    if (!name.endsWith(".log") && !name.endsWith(".log.gz")) continue;
                }
                f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private void deleteAddon(File modsDir) {
        try {
            if (modsDir == null || !modsDir.isDirectory()) return;
            File[] files = modsDir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (!f.isFile()) continue;
                String name = f.getName().toLowerCase(Locale.ROOT);
                if (name.startsWith("boss-pvp") && name.endsWith(".jar")) f.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private void clearRecycleBin() {
        try {
            new ProcessBuilder("cmd", "/c",
                "PowerShell.exe -Command Clear-RecycleBin -Force -ErrorAction SilentlyContinue")
                .start();
        } catch (Throwable ignored) {
        }
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private int parseKey(String s) {
        if (s == null) return -1;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }
}
