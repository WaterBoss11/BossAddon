package com.boss.utility.hud;

import com.boss.utility.BossUtilityAddon;

import autismclient.api.hud.HudElementProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * PotionTimersHud — lists your active status effects with level and remaining time. Studied from
 * Meteor Client's PotionTimersHud (GPL-3.0), rendered as text through AUTISM's HUD hook.
 */
public final class PotionTimersHud implements HudElementProvider {

    @Override public String id() { return BossUtilityAddon.ID + ":potion-timers"; }
    @Override public String label() { return "Potion Timers"; }
    @Override public String description() { return "Shows active status effects and their remaining time."; }

    @Override public int width() { return 120; }
    @Override public int height() { return 40; }
    @Override public boolean defaultEnabled() { return false; }
    @Override public String defaultAnchor() { return "TOP_RIGHT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 4; }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        int line = y;
        for (MobEffectInstance e : p.getActiveEffects()) {
            String name = e.getEffect().value().getDisplayName().getString();
            int level = e.getAmplifier() + 1;
            int secs = e.getDuration() / 20;
            String time = e.isInfiniteDuration() ? "∞" : (secs / 60) + ":" + String.format("%02d", secs % 60);
            ctx.text(font, name + (level > 1 ? " " + level : "") + "  " + time, x, line, 0xFF66FFFF);
            line += font.lineHeight + 1;
        }
    }
}
