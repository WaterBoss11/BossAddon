package com.boss.pvp.client.hud;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.CombatManager;
import com.boss.pvp.util.pvp.PvpUtil;
import autismclient.api.hud.HudElementProvider;
import net.minecraft.client.Minecraft;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import org.joml.Matrix3x2fStack;

public final class FovCircleHud implements HudElementProvider {

    private static final int DEFAULT_RADIUS = 60;
    private static final int RGB_SEARCHING = 0xFFFFFF;
    private static final int RGB_LOCKED    = 0xFF0000;

    private double coreHalf = 1.0;

    private static final int SEGMENTS = 128;

    private static final org.joml.Matrix3x2f[] SEG_ROT = new org.joml.Matrix3x2f[SEGMENTS];
    static {
        for (int i = 0; i < SEGMENTS; i++) {
            SEG_ROT[i] = new org.joml.Matrix3x2f().rotate((float) (2.0 * Math.PI * i / SEGMENTS));
        }
    }

    @Override public String id() { return BossPvpAddon.ID + ":fov-circle"; }
    @Override public String label() { return "Aim Assist Circle"; }
    @Override public String description() { return "Draws Aim Assist's range around your crosshair."; }

    @Override public int width()  { return radius() * 2; }
    @Override public int height() { return radius() * 2; }

    @Override public boolean defaultEnabled() { return true; }

    @Override public String defaultAnchor() { return "CENTER"; }
    @Override public int defaultX() { return 0; }
    @Override public int defaultY() {
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        return sh > 0 ? (sh - height()) / 2 : 0;
    }

    private boolean firstRenderLogged = false;

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        if (!firstRenderLogged) { firstRenderLogged = true; System.out.println("[BossPvP] HUD first render: " + id()); }
        if (!PvpUtil.hostEnabled("aim-assist")
                || !Boolean.parseBoolean(PvpUtil.hostSetting("bossaddon:pvp:aimassist", "showCircle", "true"))) return;
        coreHalf = Math.max(1, Math.min(16, option("circleThickness", 2))) / 2.0;

        int vw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int vh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        if (vw <= 0 || vh <= 0) return;
        org.joml.Vector2f center = centerForPose(ctx.pose(), vw, vh);
        if (!Float.isFinite(center.x) || !Float.isFinite(center.y)) return;
        double cx = center.x;
        double cy = center.y;

        double r = radius();
        int rgb = CombatManager.currentTarget(Minecraft.getInstance()) != null ? RGB_LOCKED : RGB_SEARCHING;
        float a = alpha <= 0 ? 1.0f : Math.min(1.0f, alpha);

        if (Boolean.parseBoolean(PvpUtil.hostSetting("bossaddon:pvp:aimassist", "filled", "false"))) {
            drawDisk(ctx, cx, cy, r - coreHalf, rgb, 0x55, a);
        }
        drawRing(ctx, cx, cy, r, rgb, a);
    }

    public static org.joml.Vector2f centerForPose(org.joml.Matrix3x2fc pose, int guiWidth, int guiHeight) {
        return new org.joml.Matrix3x2f(pose).invert()
            .transformPosition(guiWidth / 2.0f, guiHeight / 2.0f, new org.joml.Vector2f());
    }

    private int radius() {
        return Math.max(1, Math.min(512, option("circleRadius", DEFAULT_RADIUS)));
    }

    private static int option(String key, int fallback) {
        try {
            return Integer.parseInt(PvpUtil.hostSetting("bossaddon:pvp:aimassist", key, Integer.toString(fallback)));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private void drawRing(GuiGraphicsExtractor ctx, double cx, double cy, double R, int rgb, float hudAlpha) {
        float a = Math.min(1.0f, hudAlpha <= 0 ? 1.0f : hudAlpha);
        int alpha = (int) Math.round(255.0 * a);
        if (alpha <= 0) return;
        int argb = (alpha << 24) | (rgb & 0xFFFFFF);

        double thickness = Math.max(1.5, coreHalf * 2.0 + 1.0);
        int xIn = (int) Math.round(R - thickness / 2.0);
        int xOut = (int) Math.round(R + thickness / 2.0);

        int half = Math.max(1, (int) Math.ceil((Math.PI * R / SEGMENTS) * 1.2));

        Matrix3x2fStack pose = ctx.pose();
        for (int i = 0; i < SEGMENTS; i++) {
            pose.pushMatrix();
            pose.translate((float) cx, (float) cy);
            pose.mul(SEG_ROT[i]);
            ctx.fill(xIn, -half, xOut, half, argb);
            pose.popMatrix();
        }
    }

    private void drawDisk(GuiGraphicsExtractor ctx, double cx, double cy, double R, int rgb, int baseAlpha, float hudAlpha) {
        if (R <= 0) return;
        int a = Math.round(baseAlpha * hudAlpha);
        if (a <= 0) return;
        int argb = (a << 24) | rgb;
        double r2 = R * R;
        int yTop = (int) Math.ceil(cy - R);
        int yBot = (int) Math.floor(cy + R);
        for (int py = yTop; py <= yBot; py++) {
            double dy = py + 0.5 - cy;
            double s = r2 - dy * dy;
            if (s <= 0) continue;
            double half = Math.sqrt(s);
            int xL = (int) Math.round(cx - half);
            int xR = (int) Math.round(cx + half);
            if (xR > xL) ctx.fill(xL, py, xR, py + 1, argb);
        }
    }
}
