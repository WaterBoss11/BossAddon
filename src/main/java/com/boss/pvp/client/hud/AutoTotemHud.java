package com.boss.pvp.client.hud;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.CombatManager;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;

import autismclient.api.hud.HudElementProvider;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class AutoTotemHud implements HudElementProvider {

    @Override public String id() { return BossPvpAddon.ID + ":totem-pops"; }
    @Override public String label() { return "Totem Pops"; }
    @Override public String description() { return "Shows totems left and totems popped."; }

    @Override public int width() { return 120; }
    @Override public int height() { return 10; }
    @Override public boolean defaultEnabled() { return false; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 140; }

    private boolean firstRenderLogged = false;

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        if (!firstRenderLogged) { firstRenderLogged = true; System.out.println("[BossPvP] HUD first render: " + id()); }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        int count = 0;
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            var stack = mc.player.getInventory().getItem(i);
            if (stack.is(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        ctx.text(font, "Totems: " + count + "  Pops: " + CombatManager.totemPops(), x, y, 0xFFFFE066);
    }
}
