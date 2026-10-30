package com.boss.pvp.util;

import com.boss.utility.mixin.MultiPlayerGameModeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.ContainerInput;

/** Native inventory operations; no dependency on renamed client implementation helpers. */
public final class InventoryUtil {
    private InventoryUtil() {}

    public static boolean validHotbarSlot(int slot) {
        return slot >= 0 && slot < 9;
    }

    public static int hotbarButton(int handlerSlot) {
        return handlerSlot >= 36 && handlerSlot <= 44 ? handlerSlot - 36 : -1;
    }

    public static void selectHotbarSlot(Minecraft mc, int slot) {
        if (mc == null || mc.player == null || mc.gameMode == null || mc.getConnection() == null
                || !validHotbarSlot(slot) || com.boss.pvp.util.pvp.PvpUtil.hostEnabled("hide")) return;
        if (mc.player.getInventory().getSelectedSlot() == slot) return;
        mc.player.getInventory().setSelectedSlot(slot);
        ((MultiPlayerGameModeAccessor) mc.gameMode).bossutility$syncCarriedItem();
    }

    public static boolean shouldRefill(int count, int maximum, int percent) {
        return maximum > 1 && count > 0 && count < maximum && percent > 0 && percent <= 100
            && (long) count * 100L <= (long) maximum * percent;
    }

    public static boolean mergeHandlerSlots(Minecraft mc, int from, int to) {
        if (mc == null || mc.player == null || mc.gameMode == null
                || com.boss.pvp.util.pvp.PvpUtil.hostEnabled("hide")) return false;
        var player = mc.player;
        var menu = player.inventoryMenu;
        if (player.containerMenu != menu || !menu.getCarried().isEmpty()
                || from < 9 || from > 35 || hotbarButton(to) < 0) return false;
        var source = menu.getSlot(from).getItem();
        var target = menu.getSlot(to).getItem();
        if (source.isEmpty() || target.isEmpty() || target.getCount() >= target.getMaxStackSize()
                || !net.minecraft.world.item.ItemStack.isSameItemSameComponents(source, target)) return false;
        mc.gameMode.handleContainerInput(menu.containerId, from, 0, ContainerInput.PICKUP, player);
        mc.gameMode.handleContainerInput(menu.containerId, to, 0, ContainerInput.PICKUP, player);
        if (!menu.getCarried().isEmpty()) {
            mc.gameMode.handleContainerInput(menu.containerId, from, 0, ContainerInput.PICKUP, player);
        }
        return menu.getCarried().isEmpty();
    }

    public static boolean swapHandlerSlots(Minecraft mc, int from, int to) {
        if (mc == null || mc.player == null || mc.gameMode == null
                || com.boss.pvp.util.pvp.PvpUtil.hostEnabled("hide")) return false;
        var player = mc.player;
        var menu = player.inventoryMenu;
        if (player.containerMenu != menu || !menu.getCarried().isEmpty()
                || from < 5 || to < 5 || from >= menu.slots.size() || to >= menu.slots.size()) return false;
        if (from == to) return true;
        var source = menu.getSlot(from);
        var destination = menu.getSlot(to);
        if (!source.mayPickup(player) || !destination.mayPickup(player)
                || (!source.getItem().isEmpty() && !destination.mayPlace(source.getItem()))
                || (!destination.getItem().isEmpty() && !source.mayPlace(destination.getItem()))) return false;
        int button = hotbarButton(to);
        if (button >= 0) {
            mc.gameMode.handleContainerInput(menu.containerId, from, button, ContainerInput.SWAP, player);
        } else if ((button = hotbarButton(from)) >= 0) {
            mc.gameMode.handleContainerInput(menu.containerId, to, button, ContainerInput.SWAP, player);
        } else {
            mc.gameMode.handleContainerInput(menu.containerId, from, 0, ContainerInput.PICKUP, player);
            mc.gameMode.handleContainerInput(menu.containerId, to, 0, ContainerInput.PICKUP, player);
            if (!menu.getCarried().isEmpty()) {
                mc.gameMode.handleContainerInput(menu.containerId, from, 0, ContainerInput.PICKUP, player);
            }
        }
        return menu.getCarried().isEmpty();
    }
}
