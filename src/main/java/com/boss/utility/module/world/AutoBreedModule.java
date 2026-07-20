package com.boss.utility.module.world;

import com.boss.utility.BossUtilityAddon;
import com.boss.utility.util.Util;

import autismclient.modules.Module;
import autismclient.api.module.DoubleSetting;
import autismclient.api.module.RegistryListSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;

/**
 * AutoBreed — feeds breeding food to nearby animals. Studied and rewritten from Meteor Client's
 * AutoBreed (GPL-3.0). Feeds the held item to matching adult animals in range that accept it, one
 * per tick, and remembers fed animals for a while so it doesn't spam the same one.
 */
public final class AutoBreedModule extends Module {

    private final Set<Integer> fed = new HashSet<>();
    private int cooldownTicks = 0;

    public AutoBreedModule() {
        super(BossUtilityAddon.ID + ":auto-breed", "AutoBreed", "Automatically feeds nearby animals to breed them.");
        add(RegistryListSetting.entityTypes("entities", "Entities",
            "minecraft:cow|minecraft:sheep|minecraft:pig|minecraft:chicken|minecraft:horse|minecraft:wolf|minecraft:cat|minecraft:rabbit|minecraft:llama|minecraft:fox|minecraft:bee"));
        add(new DoubleSetting("range", "Range", 4.5, 1.0, 6.0, 0.5));
    }

    @Override
    public void onEnable() { fed.clear(); cooldownTicks = 0; }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) return;
        if (cooldownTicks++ > 200) { fed.clear(); cooldownTicks = 0; } // periodically forget

        double range = decimal("range");
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Animal a) || a.isBaby()) continue;
            if (!Util.matchesType(a, list("entities")) || fed.contains(a.getId())) continue;
            if (p.distanceTo(a) > range) continue;
            if (!a.isFood(p.getMainHandItem())) continue;
            Vec3 center = a.getBoundingBox().getCenter();
            Util.face(p, center);
            mc.gameMode.interact(p, a, new EntityHitResult(a, center), InteractionHand.MAIN_HAND);
            p.swing(InteractionHand.MAIN_HAND);
            fed.add(a.getId());
            return; // one per tick
        }
    }
}
