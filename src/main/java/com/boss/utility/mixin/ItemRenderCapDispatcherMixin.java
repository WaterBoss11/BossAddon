/* Adapted from boss-optimizations 6707d67; Copyright (c) 2026 Item Render Cap contributors.
 * SPDX-License-Identifier: MIT. See META-INF/licenses/BossOptimizations-MIT.txt. */
package com.boss.utility.mixin;

import com.boss.utility.itemrendercap.ItemRenderCap;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reject before render-state extraction; never override a vanilla/other-mod false with true. */
@Mixin(EntityRenderDispatcher.class)
public class ItemRenderCapDispatcherMixin {
    @Inject(method = "shouldRender(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/culling/Frustum;DDD)Z",
            at = @At("HEAD"), cancellable = true)
    private void bossutility$capItem(Entity entity, Frustum frustum, double x, double y, double z,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (!ItemRenderCap.allowRender(entity)) cir.setReturnValue(false);
    }
}
