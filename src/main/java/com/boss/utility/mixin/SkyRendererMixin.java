package com.boss.utility.mixin;

import com.boss.utility.BossUtilityAddon;
import com.boss.utility.module.render.AmbienceModule;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.dimension.DimensionType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Ambience — force an End sky everywhere. Studied from Meteor Client's SkyRendererMixin (GPL-3.0).
 * Sets the extracted sky render state's skybox to END while the module's End-sky toggle is on.
 */
@Mixin(SkyRenderer.class)
public class SkyRendererMixin {

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void bossutility$endSky(ClientLevel level, float tickDelta, Camera camera, SkyRenderState state, CallbackInfo ci) {
        AmbienceModule a = BossUtilityAddon.ambience;
        if (a != null && a.isEnabled() && a.cfgBool("endSky")) state.skybox = DimensionType.Skybox.END;
    }
}
