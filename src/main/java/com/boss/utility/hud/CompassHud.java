package com.boss.utility.hud;

import com.boss.utility.BossUtilityAddon;

import autismclient.api.hud.HudElementProvider;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

/**
 * CompassHud — shows the cardinal direction you are facing and your yaw. Studied from Meteor
 * Client's CompassHud (GPL-3.0); a compact text readout through AUTISM's HUD hook.
 */
public final class CompassHud implements HudElementProvider {

    @Override public String id() { return BossUtilityAddon.ID + ":compass"; }
    @Override public String label() { return "Compass"; }
    @Override public String description() { return "Shows the direction you are facing."; }

    @Override public int width() { return 70; }
    @Override public int height() { return 10; }
    @Override public boolean defaultEnabled() { return false; }
    @Override public String defaultAnchor() { return "TOP_CENTER"; }
    @Override public int defaultX() { return 0; }
    @Override public int defaultY() { return 4; }

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) return;
        float yaw = Mth.wrapDegrees(p.getYRot());
        Direction dir = Direction.fromYRot(yaw);
        String label = switch (dir) {
            case NORTH -> "N"; case SOUTH -> "S"; case EAST -> "E"; case WEST -> "W"; default -> "?";
        };
        ctx.text(font, label + "  " + (int) yaw + "°", x, y, 0xFFFFE066);
    }
}
