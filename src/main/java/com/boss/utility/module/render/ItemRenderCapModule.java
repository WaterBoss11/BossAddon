package com.boss.utility.module.render;

import autismclient.api.module.DoubleSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import com.boss.utility.BossUtilityAddon;
import com.boss.utility.itemrendercap.ItemRenderCap;

/** Default-off BossUtility settings for the MIT-licensed Boss Optimizations item cap port. */
public final class ItemRenderCapModule extends Module {
    public ItemRenderCapModule() {
        super(BossUtilityAddon.ID + ":itemrendercap", "Item Render Cap",
                "Draws fewer dropped items in dense piles. Inert when Boss Optimizations is installed.");
        add(new IntSetting("maxPerGroup", "Items per cell", 5, 0, 128, 1)
                .description("Highest entity IDs win; the item under the crosshair may add one extra. 0 hides all other drops.")
                .group("Render cap"));
        add(new DoubleSetting("cellWidth", "Cell width (blocks)", 4.0, 0.5, 128.0, 0.5)
                .description("Width of each grid cube, not a radius. Changes apply on the next frame.")
                .group("Render cap"));
    }

    public int maxPerGroup() { return integer("maxPerGroup"); }
    public double cellWidth() { return decimal("cellWidth"); }

    @Override public void onEnable() { ItemRenderCap.reset(); }
    @Override public void onDisable() { ItemRenderCap.reset(); }
}
