package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.MenuMode;

import autismclient.modules.Module;
import autismclient.api.module.*;
import com.boss.pvp.util.InventoryUtil;

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

    private long lastCheckNanos;

    public InvManagerModule() {
        super(BossPvpAddon.ID + ":invmanager", "InvManager", "Refills your hotbar from inventory mid-fight.");

        add(new BoolSetting("crystals", "Restock crystals", true).group("Items"));
        add(new BoolSetting("totems", "Restock totems", true).group("Items"));
        add(new BoolSetting("gapples", "Restock gapples", true).group("Items"));
        add(new BoolSetting("blocks", "Restock obsidian", true).group("Items"));
        add(new IntSetting("delay", "Delay (ms)", 150, 0, 1000, 10).group("General").visibleWhen(MenuMode::advanced));
        add(new IntSetting("threshold", "Refill below stack %", 25, 1, 100, 1).group("General"));
        add(new BoolSetting("pauseInGuis", "Pause in inventory", true)
            .description("Other containers and carried cursor stacks always pause restocking.")
            .group("General").visibleWhen(MenuMode::advanced));
    }

    @Override
    public void onDisable() {
        lastCheckNanos = 0L;
        com.boss.pvp.util.input.HeldSlotManager.clear(this);
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || p.containerMenu != p.inventoryMenu
                || !p.inventoryMenu.getCarried().isEmpty()) return;
        if (mc.gui.screen() != null && (bool("pauseInGuis")
                || !(mc.gui.screen() instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen))) return;

        long now = System.nanoTime();
        if (mc.options.keyDrop.isDown()) { lastCheckNanos = now; return; }
        long delay = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(PvpUtil.jitterMs(integer("delay")));
        if (lastCheckNanos != 0L && now - lastCheckNanos < delay) return;
        lastCheckNanos = now;

        AbstractContainerMenu menu = p.inventoryMenu;
        List<Item> wanted = new ArrayList<>();
        if (bool("crystals")) wanted.add(Items.END_CRYSTAL);
        if (bool("totems"))   wanted.add(Items.TOTEM_OF_UNDYING);
        if (bool("gapples"))  wanted.add(Items.ENCHANTED_GOLDEN_APPLE);
        if (bool("blocks"))   wanted.add(Items.OBSIDIAN);
        if (!com.boss.pvp.util.input.HeldSlotManager.tryAcquire(this, 10)) return;
        try {
            for (int dest = HOTBAR_FIRST; dest <= HOTBAR_LAST; dest++) {
                var stack = menu.getSlot(dest).getItem();
                if (stack.isEmpty() || !wanted.contains(stack.getItem())
                        || !InventoryUtil.shouldRefill(stack.getCount(), stack.getMaxStackSize(), integer("threshold"))) continue;
                int source = matchingSource(menu, stack);
                if (source < 0) continue;
                InventoryUtil.mergeHandlerSlots(mc, source, dest);
                return;
            }
            for (Item it : wanted) {
                if (findIn(menu, it, HOTBAR_FIRST, HOTBAR_LAST) >= 0) continue;
                int src = findIn(menu, it, MAIN_FIRST, MAIN_LAST);
                if (src < 0) continue;
                int dest = freeHotbar(menu);
                if (dest < 0) continue;
                InventoryUtil.swapHandlerSlots(mc, src, dest);
                return;
            }
        } finally {
            com.boss.pvp.util.input.HeldSlotManager.clear(this);
        }
    }

    private int matchingSource(AbstractContainerMenu menu, net.minecraft.world.item.ItemStack target) {
        int best = -1;
        int count = 0;
        for (int i = MAIN_FIRST; i <= MAIN_LAST; i++) {
            var stack = menu.getSlot(i).getItem();
            if (stack.getCount() > count && net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack, target)) {
                count = stack.getCount();
                best = i;
            }
        }
        return best;
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
