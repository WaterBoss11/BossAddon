package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.input.OffhandManager;

import autismclient.modules.Module;
import autismclient.api.module.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class OffhandModule extends Module {

    private static final int OFFHAND_SLOT_ID = 45;
    private static final int OFFHAND_SWAP_BUTTON = 40;

    private long lastMoveMs = 0L;

    public OffhandModule() {
        super(BossPvpAddon.ID + ":offhand", "Offhand", "Keeps your chosen item (totem, crystal, or gapple) in your offhand.");

        add(new ChoiceSetting("mode", "Mode", "Totem", "Totem", "Crystal", "Gapple-on-low", "Smart").group("General"));
        add(new DoubleSetting("lowHealth", "Gapple below HP", 12.0, 1.0, 19.0, 0.5).group("General"));
        add(new DoubleSetting("smartTotem", "Smart: totem below HP", 14.0, 1.0, 19.0, 0.5).group("Smart"));
        add(new DoubleSetting("smartGapple", "Smart: gapple below HP", 8.0, 1.0, 19.0, 0.5).group("Smart"));
        add(new IntSetting("delay", "Delay (ms)", 100, 0, 1000, 10).group("General"));
        add(new BoolSetting("pauseInGuis", "Pause in containers", true).group("General"));
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || mc.gui.screen() != null) return;

        Item want = desiredItem(p);

        if (want.equals(Items.TOTEM_OF_UNDYING) && totemModuleActive()) return;

        if (p.getOffhandItem().is(want)) return;

        boolean containerOpen = p.containerMenu != p.inventoryMenu;
        if (containerOpen && bool("pauseInGuis")) return;

        long now = System.currentTimeMillis();
        if (now - lastMoveMs < PvpUtil.jitterMs(integer("delay"))) return;

        AbstractContainerMenu menu = p.inventoryMenu;
        int slotId = findItemSlot(menu, want);
        if (slotId < 0) return;

        OffhandManager.request(this, OffhandManager.PRIORITY_OFFHAND);
        if (!OffhandManager.mayWrite(this)) return;

        mc.gameMode.handleContainerInput(menu.containerId, slotId, OFFHAND_SWAP_BUTTON, ContainerInput.SWAP, p);
        lastMoveMs = now;
    }

    @Override
    public void onDisable() {
        OffhandManager.clear(this);
    }

    private Item desiredItem(LocalPlayer p) {
        return switch (choice("mode")) {
            case "Crystal" -> Items.END_CRYSTAL;
            case "Gapple-on-low" -> p.getHealth() <= (float) decimal("lowHealth")
                ? Items.ENCHANTED_GOLDEN_APPLE : Items.TOTEM_OF_UNDYING;
            case "Smart" -> {

                if (p.getHealth() <= (float) decimal("smartGapple")) yield Items.ENCHANTED_GOLDEN_APPLE;
                if (p.getHealth() <= (float) decimal("smartTotem")) yield Items.TOTEM_OF_UNDYING;
                yield Items.END_CRYSTAL;
            }
            default -> Items.TOTEM_OF_UNDYING;
        };
    }

    private boolean totemModuleActive() {
        return BossPvpAddon.autoTotem != null && BossPvpAddon.autoTotem.isEnabled();
    }

    private int findItemSlot(AbstractContainerMenu menu, Item want) {
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i == OFFHAND_SLOT_ID) continue;
            Slot slot = menu.slots.get(i);
            if (slot == null) continue;
            ItemStack stack = slot.getItem();
            if (stack != null && stack.is(want)) return i;
        }
        return -1;
    }
}
