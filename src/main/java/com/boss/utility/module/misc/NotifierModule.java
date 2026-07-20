package com.boss.utility.module.misc;

import com.boss.utility.BossUtilityAddon;

import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Set;

/**
 * Notifier — chat notifications for player join/leave and players entering/leaving your render
 * distance (visual range). Studied and rewritten from Meteor Client's Notifier (GPL-3.0). Tick-based
 * diffing, chat output only — sends no packets.
 */
public final class NotifierModule extends Module {

    private final Set<String> tabPlayers = new HashSet<>();
    private final Set<Integer> visible = new HashSet<>();
    private boolean primed = false;

    public NotifierModule() {
        super(BossUtilityAddon.ID + ":notifier", "Notifier", "Notifies on player join/leave and render-distance changes.");
        add(new BoolSetting("joinLeave", "Join / leave", true));
        add(new BoolSetting("visualRange", "Visual range", true));
    }

    @Override
    public void onEnable() { tabPlayers.clear(); visible.clear(); primed = false; }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;

        if (bool("joinLeave")) {
            Set<String> now = new HashSet<>();
            for (PlayerInfo info : mc.getConnection().getOnlinePlayers()) {
                String name = info.getProfile().name();
                if (name != null) now.add(name);
            }
            if (primed) {
                for (String n : now) if (!tabPlayers.contains(n)) notify(mc, "§a+ " + n + " joined");
                for (String n : tabPlayers) if (!now.contains(n)) notify(mc, "§c- " + n + " left");
            }
            tabPlayers.clear();
            tabPlayers.addAll(now);
        }

        if (bool("visualRange")) {
            Set<Integer> now = new HashSet<>();
            for (Player pl : mc.level.players()) {
                if (pl == mc.player) continue;
                now.add(pl.getId());
                if (primed && !visible.contains(pl.getId())) notify(mc, "§e→ " + pl.getGameProfile().name() + " in range");
            }
            if (primed) for (int id : visible) if (!now.contains(id)) { /* left range — id-only, skip name */ }
            visible.clear();
            visible.addAll(now);
        }

        primed = true;
    }

    private void notify(Minecraft mc, String msg) {
        if (mc.player != null) mc.player.sendSystemMessage(Component.literal("§7[Notifier] §r" + msg));
    }
}
