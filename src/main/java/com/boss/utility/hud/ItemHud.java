package com.boss.utility.hud;

import com.boss.utility.BossUtilityAddon;

import autismclient.api.hud.HudElementProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * ItemHud — shows a single item icon and how many of it you have (default: totems). Studied from
 * Meteor Client's ItemHud (GPL-3.0), rendered through AUTISM's HUD submission hook.
 */
public final class ItemHud implements HudElementProvider {

    @Override public String id() { return BossUtilityAddon.ID + ":item"; }
    @Override public String label() { return "Item Counter"; }
    @Override public String description() { return "Shows how many of an item (default totems) you have."; }

    @Override public int width() { return 40; }
    @Override public int height() { return 18; }
    @Override public boolean defaultEnabled() { return false; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 4; }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        int count = countOf(p, Items.TOTEM_OF_UNDYING);
        ItemStack icon = new ItemStack(Items.TOTEM_OF_UNDYING);
        ctx.item(icon, x, y);
        ctx.text(font, "x " + count, x + 18, y + 5, 0xFFFFFFFF);
    }

    private int countOf(LocalPlayer p, net.minecraft.world.item.Item item) {
        int c = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) c += s.getCount();
        }
        return c;
    }
}
