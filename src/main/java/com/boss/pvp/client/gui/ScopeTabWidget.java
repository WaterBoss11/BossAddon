package com.boss.pvp.client.gui;

import com.boss.pvp.relay.RelayManager;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * One clickable tab in the BossChat scope-tab bar (added above the chat input by {@code ChatScreenMixin}).
 * Clicking a tab switches the BossChat send-scope — the same effect as {@code ?bossaddon chat global|server|
 * party}; the DM tab points typed chat at the remembered DM target. The single source of truth for which tab is
 * highlighted is {@link RelayManager#mode()}, so the bar always agrees with command-based scope switching.
 *
 * <p>Colours follow BossAddon's chat conventions (aqua global / green server / gold party / purple DM) as a top
 * accent on the active tab; inactive tabs are a muted dark. The exact pixels/shades are a first pass — worth an
 * in-game look before final sign-off.
 */
public final class ScopeTabWidget extends AbstractWidget {

    private static final int BG_INACTIVE = 0xE0161616;   // muted dark
    private static final int BG_HOVER    = 0xF02C2C2C;
    private static final int BG_ACTIVE   = 0xF04A4A4A;    // clearly brighter than inactive
    private static final int FG_INACTIVE = 0xFF8A9199;    // dimmer, still readable
    private static final int FG_ACTIVE   = 0xFFFFFFFF;
    private static final int BORDER      = 0xFF000000;

    private final RelayManager.Mode scope;   // null for the DM tab
    private final boolean dm;
    private final int accent;
    private final String text;

    public ScopeTabWidget(int x, int y, int w, int h, String label, RelayManager.Mode scope, boolean dm, int accent) {
        super(x, y, w, h, Component.literal(label));
        this.text = label;
        this.scope = scope;
        this.dm = dm;
        this.accent = accent;
    }

    private boolean isActiveScope() {
        RelayManager.Mode m = RelayManager.get().mode();
        return dm ? m == RelayManager.Mode.DM : m == scope;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        boolean on = isActiveScope();
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        ctx.fill(x, y, x + w, y + h, on ? BG_ACTIVE : (hover ? BG_HOVER : BG_INACTIVE));
        // thin borders so the row reads as a bar of distinct rectangular tabs
        ctx.fill(x, y, x + w, y + 1, BORDER);
        ctx.fill(x, y + h - 1, x + w, y + h, BORDER);
        ctx.fill(x + w - 1, y, x + w, y + h, BORDER);
        if (on) ctx.fill(x, y, x + w, y + 2, accent);   // scope-coloured accent on the active tab
        Font font = Minecraft.getInstance().font;
        int tx = x + (w - font.width(text)) / 2;
        int ty = y + (h - 8) / 2;
        ctx.text(font, text, tx, ty, on ? FG_ACTIVE : FG_INACTIVE, true);
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        RelayManager r = RelayManager.get();
        if (dm) {
            r.toggleDmScope();
        } else {
            r.toggleScope(scope);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // The visible label plus active highlight is sufficient; no extra narration.
    }
}
