/*
 * Adapted from WaterBoss11/boss-optimizations, revision 6707d67b15741fe9c36d94a1356e7c78ef9d9780.
 * Copyright (c) 2026 Item Render Cap contributors. SPDX-License-Identifier: MIT
 * Full upstream notice: META-INF/licenses/BossOptimizations-MIT.txt.
 */
package com.boss.utility.itemrendercap;

import com.boss.pvp.util.AddonHalves;
import com.boss.utility.BossUtilityAddon;
import com.boss.utility.module.render.ItemRenderCapModule;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Visual-only adapter: no entity, packet, ticking, picking, pickup or despawn state is changed. */
public final class ItemRenderCap {
    private static final double LOOK_RANGE = 32.0D;
    private static final double LOOK_TOLERANCE = 0.25D;
    // The standalone mod owns its own configuration. Never double-cap or access/change its settings.
    private static final boolean STANDALONE_PRESENT = FabricLoader.getInstance().isModLoaded("bossoptimizations");
    private static final ItemRenderFrame FRAME = new ItemRenderFrame();

    private ItemRenderCap() { }

    /** Called only at the extraction boundary, including empty-item frames. Settings apply next frame. */
    public static void beginFrame() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        ItemRenderCapModule module = BossUtilityAddon.itemRenderCap;
        boolean moduleEnabled = module != null && module.isEnabled();
        FRAME.begin(level, moduleEnabled, AddonHalves.utilityOn(), STANDALONE_PRESENT,
                moduleEnabled ? module.maxPerGroup() : 5,
                moduleEnabled ? module.cellWidth() : 4.0D,
                selector -> collect(mc, level, selector));
    }

    /** False only rejects an item. The mixin never forces vanilla/other-mod visibility to true. */
    public static boolean allowRender(Entity entity) {
        if (!(entity instanceof ItemEntity)) return true;
        ItemRenderCapModule module = BossUtilityAddon.itemRenderCap;
        return FRAME.allow(Minecraft.getInstance().level, module != null && module.isEnabled(),
                AddonHalves.utilityOn(), STANDALONE_PRESENT, entity.getId());
    }

    /** Enable/disable/disconnect invalidate selection, but never build during half-toggle callbacks. */
    public static void reset() {
        FRAME.reset();
    }

    private static int collect(Minecraft mc, ClientLevel level, ItemGroupSelector selector) {
        Entity camera = mc.getCameraEntity();
        Vec3 eye = camera == null ? null : camera.getEyePosition(1.0F);
        Vec3 reach = camera == null ? null : eye.add(camera.getViewVector(1.0F).scale(LOOK_RANGE));
        int lookedAt = ItemGroupSelector.NO_ID;
        double nearest = Double.MAX_VALUE;

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity item)) continue;
            if (eye != null) {
                var hit = item.getBoundingBox().inflate(LOOK_TOLERANCE).clip(eye, reach);
                if (hit.isPresent()) {
                    double distance = eye.distanceToSqr(hit.get());
                    if (distance < nearest) {
                        nearest = distance;
                        lookedAt = item.getId();
                    }
                }
            }
            Vec3 position = item.position();
            selector.offer(item.getId(), position.x, position.y, position.z);
        }
        // Preserve the donor's visual exemption, including items made pickable by another mod.
        // This ray does not clip against walls and does not change Minecraft's actual hit result.
        if (lookedAt == ItemGroupSelector.NO_ID && mc.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof ItemEntity item) {
            lookedAt = item.getId();
        }
        return lookedAt;
    }
}
