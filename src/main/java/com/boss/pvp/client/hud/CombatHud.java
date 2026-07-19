package com.boss.pvp.client.hud;

import com.boss.pvp.BossPvpAddon;

import autismclient.api.hud.HudElementProvider;
import autismclient.modules.Module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;

public final class CombatHud implements HudElementProvider {

    private static final int LOW_BLOCK = 5;

    @Override public String id() { return BossPvpAddon.ID + ":combat-hud"; }
    @Override public String label() { return "Combat HUD"; }
    @Override public String description() { return "Target health, item counts, and active modules."; }

    @Override public int width()  { return 150; }
    @Override public int height() { return 47; }
    @Override public boolean defaultEnabled() { return false; }
    @Override public String defaultAnchor() { return "TOP_LEFT"; }
    @Override public int defaultX() { return 4; }
    @Override public int defaultY() { return 160; }

    private boolean firstRenderLogged = false;

    // Per-tick cache: the inventory scans + active-modules string are recomputed once per game tick
    // (when gameTime changes) instead of on every rendered frame.
    private long cacheTick = Long.MIN_VALUE;
    private int cBlocks;
    private String leftStr = "T:0  C:0  G:0  B:";
    private String onStr = "-";

    @Override
    public void render(GuiGraphicsExtractor ctx, Font font, int x, int y, float alpha) {
        if (!firstRenderLogged) { firstRenderLogged = true; System.out.println("[BossPvP] HUD first render: " + id()); }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null || mc.level == null) return;

        long now = mc.level.getGameTime();
        if (now != cacheTick) {
            cacheTick = now;
            int gap = count(mc, Items.ENCHANTED_GOLDEN_APPLE) + count(mc, Items.GOLDEN_APPLE);
            leftStr = "T:" + count(mc, Items.TOTEM_OF_UNDYING) + "  C:" + count(mc, Items.END_CRYSTAL)
                + "  G:" + gap + "  B:";
            cBlocks = fullCubeCount(mc);
            onStr = activeModules();
        }

        int line = y;
        LivingEntity t = (BossPvpAddon.killAura != null && BossPvpAddon.killAura.isEnabled())
            ? BossPvpAddon.killAura.currentTarget() : null;
        if (t != null) {
            int hp = (int) Math.ceil(t.getHealth() + t.getAbsorptionAmount());
            ctx.text(font, "Target: " + t.getName().getString() + " " + hp + "hp", x, line, 0xFFFF6655);
        } else {
            ctx.text(font, "Target: -", x, line, 0xFFAAAAAA);
        }
        line += 11;

        ctx.text(font, leftStr, x, line, 0xFFFFE066);

        boolean low = cBlocks < LOW_BLOCK;
        boolean blink = (System.currentTimeMillis() / 300L) % 2L == 0L;
        int bColor = (low && blink) ? 0xFFFF4040 : 0xFFFFE066;
        ctx.text(font, Integer.toString(cBlocks), x + font.width(leftStr), line, bColor);
        line += 11;

        ctx.text(font, "On: " + onStr, x, line, 0xFF66FF99);
        line += 11;

        // Attack-cooldown bar (green when fully charged, orange while recharging).
        float cd = Math.max(0.0f, Math.min(1.0f, mc.player.getAttackStrengthScale(0.0f)));
        int barW = 120;
        int filled = Math.round(barW * cd);
        ctx.fill(x, line, x + barW, line + 3, 0xFF333333);
        if (filled > 0) ctx.fill(x, line, x + filled, line + 3, cd >= 1.0f ? 0xFF55FF55 : 0xFFFFAA33);
    }

    private int fullCubeCount(Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        int c = 0;
        for (int i = 0; i <= 8; i++) {
            ItemStack s = inv.getItem(i);
            if (s.getItem() instanceof BlockItem bi
                && bi.getBlock().defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                c += s.getCount();
            }
        }
        return c;
    }

    private int count(Minecraft mc, Item it) {
        Inventory inv = mc.player.getInventory();
        int c = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(it)) c += inv.getItem(i).getCount();
        }
        return c;
    }

    private String activeModules() {
        StringBuilder sb = new StringBuilder();
        add(sb, "Crystal", BossPvpAddon.autoCrystal);
        add(sb, "KA", BossPvpAddon.killAura);
        add(sb, "Surround", BossPvpAddon.surround);
        add(sb, "Anchor", BossPvpAddon.autoAnchor);
        add(sb, "Bed", BossPvpAddon.bedAura);
        add(sb, "Totem", BossPvpAddon.autoTotem);
        add(sb, "Pot", BossPvpAddon.autoPot);
        add(sb, "Gap", BossPvpAddon.autoGap);
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private void add(StringBuilder sb, String name, Module m) {
        if (m != null && m.isEnabled()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(name);
        }
    }
}
