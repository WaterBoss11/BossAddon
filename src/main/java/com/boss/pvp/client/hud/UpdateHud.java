package com.boss.pvp.client.hud;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.update.UpdateChecker;

import autismclient.api.hud.HudElementProvider;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Small passive HUD indicator that appears only when the launch-time update check found a newer release. It
 * renders nothing when up to date, so it's invisible in the normal case — a persistent-but-quiet backstop in
 * case the one-time chat notice was missed. Read-only: it just displays the version numbers.
 */
public final class UpdateHud implements HudElementProvider {

    @Override public String id() { return BossPvpAddon.ID + ":update"; }
    @Override public String label() { return "Update Notice"; }
    @Override public String description() { return "Shows a small notice when a newer Boss's PVP release is available."; }

    @Override public int width() { return 180; }
    @Override public int height() { return 10; }
    @Override public boolean defaultEnabled() { return true; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 4; }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        if (!UpdateChecker.isOutdated()) return;
        String text = "[Update] Boss's PVP v" + UpdateChecker.currentVersion()
            + " -> v" + UpdateChecker.latestVersion() + " available";
        ctx.text(font, text, x, y, 0xFFFF5555);
    }
}
