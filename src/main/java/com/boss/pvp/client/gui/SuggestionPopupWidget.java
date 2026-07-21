package com.boss.pvp.client.gui;

import com.boss.pvp.client.SuggestionCycle;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * A small custom-drawn suggestion popup for "?" commands — the same visual role as vanilla's "/" command
 * dropdown, but drawn by us. Vanilla's {@code CommandSuggestions} is hardcoded to "/" and its own dispatcher
 * type, so it can't be reused for "?"; this box instead lists the current completions from BossAddon's own
 * {@link com.boss.pvp.BossAddonInit#suggest} logic (fed live by the chat mixin via {@link BossSuggestionSource}),
 * highlighting whichever entry the Tab-cycle currently selects. It is <b>purely visual feedback</b>: Tab still
 * does the actual insertion in the mixin; this only shows what the options are and which one is next.
 *
 * <p>Added above the chat input like {@link ScopeTabWidget}, reusing the same {@code extractWidgetRenderState}
 * render path. It is non-interactive ({@link #isMouseOver} always false) so clicks pass straight through to the
 * chat log beneath it. The box is bottom-anchored to {@code getY()+getHeight()} and grows upward with the number
 * of rows, and its width tracks the widest entry, so an empty list draws nothing at all.
 *
 * <p>The exact pixels/shades are a first pass — the list content and highlight are unit-tested, but the on-screen
 * look (position, sizing, colours) needs an in-game check before final sign-off.
 */
public final class SuggestionPopupWidget extends AbstractWidget {

    private static final int MAX_ROWS = 8;   // our longest list is ~6 subcommands; guard anyway
    private static final int ROW_H    = 12;
    private static final int PAD_X     = 4;

    private static final int BORDER = 0xFF000000;
    private static final int BG     = 0xE0161616;   // matches the scope-tab dark
    private static final int BG_SEL = 0xF04A4A4A;   // highlighted row (what Tab selects)
    private static final int FG     = 0xFFB8C0C8;   // normal entry
    private static final int FG_SEL = 0xFFFFFFFF;   // highlighted entry
    private static final int ACCENT = 0xFF4FC3F7;   // thin marker on the highlighted row (aqua, BossChat accent)

    private final transient BossSuggestionSource source;

    public SuggestionPopupWidget(int x, int y, int w, int h, BossSuggestionSource source) {
        super(x, y, w, h, Component.literal("BossAddon suggestions"));
        this.source = source;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float partialTick) {
        BossSuggestionSource.Model model = source == null ? null : source.bossSuggestionModel();
        if (model == null) return;
        List<String> items = model.items();
        if (items == null || items.isEmpty()) return;

        Font font = Minecraft.getInstance().font;
        int rows = Math.min(items.size(), MAX_ROWS);
        int textW = 0;
        for (int i = 0; i < rows; i++) textW = Math.max(textW, font.width(items.get(i)));

        int left = getX();
        int right = left + textW + PAD_X * 2;
        int bottom = getY() + getHeight();     // bottom-anchored: sits just above the tab bar / chat input
        int top = bottom - rows * ROW_H;

        // border then fill, so the box reads as a distinct panel over the chat log
        ctx.fill(left - 1, top - 1, right + 1, bottom + 1, BORDER);
        ctx.fill(left, top, right, bottom, BG);

        int hi = SuggestionCycle.clampIndex(model.highlight(), items.size());
        for (int i = 0; i < rows; i++) {
            int ry = top + i * ROW_H;
            boolean sel = i == hi;
            if (sel) {
                ctx.fill(left, ry, right, ry + ROW_H, BG_SEL);
                ctx.fill(left, ry, left + 2, ry + ROW_H, ACCENT);   // accent marker ties it to the tab-cycle
            }
            ctx.text(font, items.get(i), left + PAD_X, ry + (ROW_H - 8) / 2, sel ? FG_SEL : FG, true);
        }
    }

    /** Non-interactive: never claim the mouse, so clicks fall through to the chat log underneath. */
    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return false;
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        // purely visual; never invoked because isMouseOver is always false
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        // decorative feedback for the chat input; the input box itself carries narration
    }
}
