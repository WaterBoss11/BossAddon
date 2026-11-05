package com.boss.pvp.test;

import com.boss.pvp.util.AddonHalves;
import com.boss.utility.BossUtilityAddon;
import com.boss.utility.itemrendercap.ItemRenderCap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/** Isolated client-world adapter checks. These are not server pickup/despawn or FPS benchmarks. */
final class BossQaItemRenderCap {
    private BossQaItemRenderCap() { }

    static void check(Minecraft client) {
        BossQaIsolation.profile();
        BossQaIsolation.require(client.level != null && client.player != null, "Item cap fixture needs a world");
        BossQaIsolation.require(!FabricLoader.getInstance().isModLoaded("bossoptimizations"),
                "Standalone donor must be absent for the addon-active fixture");
        BossQaIsolation.require(AddonHalves.utilityOn(), "Fresh utility half must be on");
        var module = BossUtilityAddon.itemRenderCap;
        boolean previousEnabled = module.isEnabled();
        String previousMax = module.value("maxPerGroup");
        String previousWidth = module.value("cellWidth");
        var previousHit = client.hitResult;
        List<ItemEntity> items = new ArrayList<>();
        try {
            // Far above the player: the donor's independent crosshair ray cannot intersect this pile.
            for (int i = 0; i < 8; i++) {
                ItemEntity item = new ItemEntity(client.level, 1.1, 1000.1, 1.1, new ItemStack(Items.STONE));
                items.add(item);
                client.level.addEntity(item);
            }
            module.setValueTransient("maxPerGroup", "1");
            module.setValueTransient("cellWidth", "4.0");
            module.setEnabled(true);
            client.hitResult = null;
            ItemRenderCap.beginFrame();
            long visible = items.stream().filter(ItemRenderCap::allowRender).count();
            BossQaIsolation.require(visible == 1, "One cell must contribute exactly one unforced item");
            ItemEntity hidden = items.stream().filter(item -> !ItemRenderCap.allowRender(item)).findFirst().orElseThrow();
            var position = hidden.position();
            var velocity = hidden.getDeltaMovement();
            int ticks = hidden.tickCount;
            for (int i = 0; i < 100; i++) {
                BossQaIsolation.require(!ItemRenderCap.allowRender(hidden), "Query changed frame membership");
            }
            BossQaIsolation.require(!hidden.isRemoved() && hidden.getItem().getCount() == 1
                    && hidden.position().equals(position) && hidden.getDeltaMovement().equals(velocity)
                    && hidden.tickCount == ticks && client.level.getEntity(hidden.getId()) == hidden,
                    "Render decisions mutated or removed an item");
            BossQaIsolation.require(ItemRenderCap.allowRender(client.player), "Non-item render decision changed");

            module.setValueTransient("maxPerGroup", "8");
            BossQaIsolation.require(!ItemRenderCap.allowRender(hidden), "Settings changed an in-progress frame");
            ItemRenderCap.beginFrame();
            BossQaIsolation.require(items.stream().allMatch(ItemRenderCap::allowRender), "Next frame missed changed settings");
            module.setValueTransient("maxPerGroup", "1");
            ItemRenderCap.beginFrame();
            AddonHalves.setHalf(AddonHalves.UTILITY, false);
            BossQaIsolation.require(items.stream().allMatch(ItemRenderCap::allowRender), "Utility-off still caps items");
            AddonHalves.setHalf(AddonHalves.UTILITY, true);
            BossQaIsolation.require(module.isEnabled() && module.maxPerGroup() == 1 && module.cellWidth() == 4.0,
                    "Half restoration changed settings or forgot the enabled cap");
            ItemRenderCap.beginFrame();
            BossQaIsolation.require(items.stream().filter(ItemRenderCap::allowRender).count() == 1, "Restored cap inactive");
            module.setEnabled(false);
            BossQaIsolation.require(items.stream().allMatch(ItemRenderCap::allowRender), "Module-off still caps items");
        } finally {
            if (!AddonHalves.utilityOn()) AddonHalves.setHalf(AddonHalves.UTILITY, true);
            module.setEnabled(false);
            module.setValueTransient("maxPerGroup", previousMax);
            module.setValueTransient("cellWidth", previousWidth);
            module.setEnabled(previousEnabled);
            client.hitResult = previousHit;
            for (ItemEntity item : items) client.level.removeEntity(item.getId(), Entity.RemovalReason.DISCARDED);
            ItemRenderCap.reset();
        }
    }
}
