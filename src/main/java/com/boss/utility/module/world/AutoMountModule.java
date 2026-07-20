package com.boss.utility.module.world;

import com.boss.utility.BossUtilityAddon;
import com.boss.utility.util.Util;

import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.api.module.RegistryListSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * AutoMount — automatically rides nearby rideable entities. Studied and rewritten from Meteor
 * Client's AutoMount (GPL-3.0). One interaction per tick, faces the entity first.
 */
public final class AutoMountModule extends Module {

    public AutoMountModule() {
        super(BossUtilityAddon.ID + ":auto-mount", "AutoMount", "Automatically rides nearby entities.");
        add(RegistryListSetting.entityTypes("entities", "Entities",
            "minecraft:horse|minecraft:donkey|minecraft:mule|minecraft:camel|minecraft:pig|minecraft:strider|minecraft:skeleton_horse|minecraft:llama"));
        add(new DoubleSetting("range", "Range", 4.0, 1.0, 6.0, 0.5));
        add(new BoolSetting("rotate", "Face entity", true));
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) return;
        if (p.isPassenger() || p.isShiftKeyDown()) return;

        double range = decimal("range");
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || !Util.matchesType(e, list("entities"))) continue;
            if (p.distanceTo(e) > range) continue;
            Vec3 center = e.getBoundingBox().getCenter();
            if (bool("rotate")) Util.face(p, center);
            mc.gameMode.interact(p, e, new EntityHitResult(e, center), InteractionHand.MAIN_HAND);
            return; // one per tick
        }
    }
}
