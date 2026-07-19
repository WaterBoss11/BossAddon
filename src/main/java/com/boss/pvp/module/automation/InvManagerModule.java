package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.MenuMode;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismInventoryHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

public final class InvManagerModule extends Module {

    private static final int MAIN_FIRST = 9, MAIN_LAST = 35, HOTBAR_FIRST = 36, HOTBAR_LAST = 44;

    private long lastCheckMs = 0L;

    public InvManagerModule() {
        super(BossPvpAddon.ID + ":invmanager", "InvManager", "Refills your hotbar from inventory mid-fight.");

        add(new BoolSetting("crystals", "Restock crystals", true).group("Items"));
        add(new BoolSetting("totems", "Restock totems", true).group("Items"));
        add(new BoolSetting("gapples", "Restock gapples", true).group("Items"));
        add(new BoolSetting("blocks", "Restock obsidian", true).group("Items"));
        add(new IntSetting("delay", "Delay (ms)", 150, 0, 1000, 10).group("General").visibleWhen(MenuMode::advanced));
        add(new BoolSetting("pauseInGuis", "Pause in containers", true).group("General").visibleWhen(MenuMode::advanced));
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || mc.gui.screen() != null) return;

        boolean containerOpen = p.containerMenu != p.inventoryMenu;
        if (containerOpen && bool("pauseInGuis")) return;

        long now = System.currentTimeMillis();
        if (now - lastCheckMs < PvpUtil.jitterMs(integer("delay"))) return;
        lastCheckMs = now;

        AbstractContainerMenu menu = p.inventoryMenu;
        List<Item> wanted = new ArrayList<>();
        if (bool("crystals")) wanted.add(Items.END_CRYSTAL);
        if (bool("totems"))   wanted.add(Items.TOTEM_OF_UNDYING);
        if (bool("gapples"))  wanted.add(Items.ENCHANTED_GOLDEN_APPLE);
        if (bool("blocks"))   wanted.add(Items.OBSIDIAN);

        for (Item it : wanted) {
            if (findIn(menu, it, HOTBAR_FIRST, HOTBAR_LAST) >= 0) continue;
            int src = findIn(menu, it, MAIN_FIRST, MAIN_LAST);
            if (src < 0) continue;
            int dest = freeHotbar(menu);
            if (dest < 0) continue;
            AutismInventoryHelper.swapHandlerSlots(mc, src, dest);
            return;
        }
    }

    private int findIn(AbstractContainerMenu menu, Item it, int from, int to) {
        for (int i = from; i <= to && i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s != null && s.getItem().is(it)) return i;
        }
        return -1;
    }

    private int freeHotbar(AbstractContainerMenu menu) {
        for (int i = HOTBAR_FIRST; i <= HOTBAR_LAST && i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s != null && s.getItem().isEmpty()) return i;
        }
        return -1;
    }
}
