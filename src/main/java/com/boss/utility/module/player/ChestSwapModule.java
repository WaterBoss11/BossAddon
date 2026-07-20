package com.boss.utility.module.player;

import com.boss.utility.BossUtilityAddon;

import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * ChestSwap — one-shot swap between a chestplate and an elytra. Studied and rewritten from Meteor
 * Client's ChestSwap (GPL-3.0). Triggers on enable (module toggles itself back off), swapping via a
 * three-click pickup so it's a single manual action rather than continuous automation.
 */
public final class ChestSwapModule extends Module {

    private static final int CHEST_ARMOR_SLOT = 6; // inventory-menu index of the chest armour slot

    public ChestSwapModule() {
        super(BossUtilityAddon.ID + ":chest-swap", "ChestSwap", "Swaps between a chestplate and an elytra.");
        add(new BoolSetting("netherite", "Prefer netherite", true));
        add(new BoolSetting("closeInventory", "Close inventory after", true));
    }

    @Override
    public void onEnable() {
        swap();
        setEnabled(false); // one-shot
    }

    private void swap() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return;

        ItemStack chest = p.getItemBySlot(EquipmentSlot.CHEST);
        boolean wearingElytra = chest.has(DataComponents.GLIDER);
        int src = wearingElytra ? findChestplate(p) : findElytra(p);
        if (src < 0) return;

        int id = p.inventoryMenu.containerId;
        mc.gameMode.handleContainerInput(id, src, 0, ContainerInput.PICKUP, p);              // grab replacement
        mc.gameMode.handleContainerInput(id, CHEST_ARMOR_SLOT, 0, ContainerInput.PICKUP, p); // place it, grab old
        mc.gameMode.handleContainerInput(id, src, 0, ContainerInput.PICKUP, p);              // stash old back
        if (bool("closeInventory")) p.connection.send(new ServerboundContainerClosePacket(id));
    }

    private int findElytra(LocalPlayer p) {
        for (int i = 0; i < p.inventoryMenu.slots.size(); i++) {
            if (p.inventoryMenu.slots.get(i).getItem().has(DataComponents.GLIDER)) return i;
        }
        return -1;
    }

    private int findChestplate(LocalPlayer p) {
        int diamond = -1, netherite = -1;
        for (int i = 0; i < p.inventoryMenu.slots.size(); i++) {
            ItemStack s = p.inventoryMenu.slots.get(i).getItem();
            if (s.is(Items.NETHERITE_CHESTPLATE)) netherite = i;
            else if (s.is(Items.DIAMOND_CHESTPLATE)) diamond = i;
        }
        if (bool("netherite")) return netherite >= 0 ? netherite : diamond;
        return diamond >= 0 ? diamond : netherite;
    }
}
