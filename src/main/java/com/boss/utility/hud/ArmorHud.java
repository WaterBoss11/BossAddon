package com.boss.utility.hud;

import com.boss.utility.BossUtilityAddon;

import autismclient.api.hud.HudElementProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * ArmorHud — shows your four armour pieces with their durability. Studied from Meteor Client's
 * ArmorHud (GPL-3.0), rendered as item icons + vanilla decorations through AUTISM's HUD hook.
 */
public final class ArmorHud implements HudElementProvider {

    private static final EquipmentSlot[] SLOTS = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    @Override public String id() { return BossUtilityAddon.ID + ":armor"; }
    @Override public String label() { return "Armor HUD"; }
    @Override public String description() { return "Shows your armour pieces and their durability."; }

    @Override public int width() { return SLOTS.length * 20; }
    @Override public int height() { return 18; }
    @Override public boolean defaultEnabled() { return false; }
    @Override public String defaultAnchor() { return "BOTTOM_LEFT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 40; }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        int ix = x;
        for (EquipmentSlot slot : SLOTS) {
            ItemStack s = p.getItemBySlot(slot);
            if (!s.isEmpty()) {
                ctx.item(s, ix, y);
                ctx.itemDecorations(font, s, ix, y);
            }
            ix += 20;
        }
    }
}
