/* Adapted from boss-optimizations 6707d67; Copyright (c) 2026 Item Render Cap contributors.
 * SPDX-License-Identifier: MIT. See META-INF/licenses/BossOptimizations-MIT.txt. */
package com.boss.utility.mixin;

import com.boss.utility.itemrendercap.ItemRenderCap;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Snapshot once per extraction pass, never midway through entity queries or on a timer. */
@Mixin(LevelExtractor.class)
public class ItemRenderCapFrameMixin {
    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Lnet/minecraft/client/Camera;F)V", at = @At("HEAD"))
    private void bossutility$itemFrame(DeltaTracker deltaTracker, Camera camera, float partialTick, CallbackInfo ci) {
        ItemRenderCap.beginFrame();
    }
}
