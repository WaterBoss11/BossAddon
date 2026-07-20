package com.boss.utility.module.world;

import com.boss.utility.BossUtilityAddon;
import com.boss.utility.util.Util;

import autismclient.modules.Module;
import autismclient.api.module.DoubleSetting;
import autismclient.util.AutismInventoryHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * AutoShearer — shears nearby unsheared adult sheep. Studied and rewritten from Meteor Client's
 * AutoShearer (GPL-3.0). Selects shears in the hotbar, shears one sheep per tick, restores the slot.
 */
public final class AutoShearerModule extends Module {

    public AutoShearerModule() {
        super(BossUtilityAddon.ID + ":auto-shearer", "AutoShearer", "Automatically shears nearby sheep.");
        add(new DoubleSetting("range", "Range", 5.0, 1.0, 6.0, 0.5));
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) return;

        double range = decimal("range");
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Sheep sheep) || sheep.isSheared() || sheep.isBaby()) continue;
            if (p.distanceTo(sheep) > range) continue;

            int shearsSlot = findShears(p);
            if (shearsSlot < 0) return;
            int prev = p.getInventory().getSelectedSlot();
            AutismInventoryHelper.selectHotbarSlot(mc, shearsSlot);

            Vec3 center = sheep.getBoundingBox().getCenter();
            Util.face(p, center);
            mc.gameMode.interact(p, sheep, new EntityHitResult(sheep, center), InteractionHand.MAIN_HAND);

            if (prev != shearsSlot) AutismInventoryHelper.selectHotbarSlot(mc, prev);
            return; // one per tick
        }
    }

    private int findShears(LocalPlayer p) {
        for (int i = 0; i <= 8; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.is(Items.SHEARS)) return i;
        }
        return -1;
    }
}
