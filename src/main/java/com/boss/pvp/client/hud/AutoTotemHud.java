package com.boss.pvp.client.hud;

import com.boss.pvp.module.automation.AutoTotemModule;

import com.boss.pvp.BossPvpAddon;

import autismclient.api.hud.HudElementProvider;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class AutoTotemHud implements HudElementProvider {

    @Override public String id() { return BossPvpAddon.ID + ":totem-pops"; }
    @Override public String label() { return "Totem Pops"; }
    @Override public String description() { return "Shows how many totems AutoTotem has popped."; }

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
        AutoTotemModule m = BossPvpAddon.autoTotem;
        if (m == null || !m.isEnabled()) return;
        ctx.text(font, "Totems: " + m.totemCount() + "  Pops: " + m.popCount(), x, y, 0xFFFFE066);
    }
}
