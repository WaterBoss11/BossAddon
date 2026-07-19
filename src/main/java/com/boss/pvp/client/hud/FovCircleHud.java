package com.boss.pvp.client.hud;

import com.boss.pvp.module.combat.AimAssistModule;

import com.boss.pvp.BossPvpAddon;

import autismclient.api.hud.HudElementProvider;
import autismclient.util.AutismUiScale;

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
    @Override public String description() { return "Draws Aim Assist's aiming range as a circle around your crosshair (turns red when locked on)."; }

    @Override public int width()  { return radius() * 2; }
    @Override public int height() { return radius() * 2; }

    @Override public boolean defaultEnabled() { return true; }

    @Override public String defaultAnchor() { return "CENTER"; }
    @Override public int defaultX() { return 0; }
    @Override public int defaultY() {
        int sh = AutismUiScale.getVirtualScreenHeight();
        return sh > 0 ? (sh - height()) / 2 : 0;
    }

    private boolean firstRenderLogged = false;

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        if (!firstRenderLogged) { firstRenderLogged = true; System.out.println("[BossPvP] HUD first render: " + id()); }
        AimAssistModule m = BossPvpAddon.aimAssist;
        if (m == null || !m.isEnabled() || !m.showCircle()) return;
        coreHalf = m.circleCoreHalf();

        int vw = AutismUiScale.getVirtualScreenWidth();
        int vh = AutismUiScale.getVirtualScreenHeight();
        if (vw <= 0 || vh <= 0) return;
        double cx = vw / 2.0;
        double cy = vh / 2.0;

        double r = radius();
        int rgb = m.isLockedOn() ? RGB_LOCKED : RGB_SEARCHING;
        float a = alpha <= 0 ? 1.0f : Math.min(1.0f, alpha);

        if (m.circleFilled()) {
            drawDisk(ctx, cx, cy, r - coreHalf, rgb, 0x55, a);
        }
        drawRing(ctx, cx, cy, r, rgb, a);
    }

    private int radius() {
        AimAssistModule m = BossPvpAddon.aimAssist;
        int r = (m != null) ? m.circleRadius() : DEFAULT_RADIUS;
        return Math.max(1, r);
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
