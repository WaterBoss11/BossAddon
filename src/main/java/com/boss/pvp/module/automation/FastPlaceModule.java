package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;
import autismclient.api.module.*;

import net.minecraft.world.item.BedItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class FastPlaceModule extends Module {

    public FastPlaceModule() {
        super(BossPvpAddon.ID + ":fastplace", "FastPlace", "Remove the right-click place/use cooldown.");

        add(new IntSetting("placeRate", "Cooldown ticks", 0, 0, 4, 1)
            .description("0 = no cooldown (place every tick). Vanilla is 4.").group("General"));
        add(new ChoiceSetting("scope", "Scope", "Both", "Both", "Blocks", "PvP items").group("General"));
    }

    public int placeRate() { return integer("placeRate"); }

    public boolean appliesTo(ItemStack held) {
        if (held == null || held.isEmpty()) return false;
        return switch (choice("scope")) {
            case "Blocks" -> held.getItem() instanceof BlockItem;
            case "PvP items" -> isPvpItem(held);
            default -> true;
        };
    }

    private boolean isPvpItem(ItemStack s) {
        return s.is(Items.END_CRYSTAL) || s.is(Items.RESPAWN_ANCHOR) || s.is(Items.GLOWSTONE)
            || s.is(Items.OBSIDIAN) || s.is(Items.CRYING_OBSIDIAN) || s.getItem() instanceof BedItem;
    }
}
