package com.boss.utility.mixin;

import com.boss.utility.BossUtilityAddon;
import com.boss.utility.module.render.AmbienceModule;

import net.minecraft.client.renderer.fog.FogRenderer;

import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Ambience — custom fog colour. Studied from Meteor Client's FogRendererMixin (GPL-3.0). Replaces the
 * fog colour vector written to the GPU buffer with the module's configured colour.
 */
@Mixin(FogRenderer.class)
public class FogRendererMixin {

    @ModifyVariable(
        method = "updateBuffer(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V",
        at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vector4f bossutility$fogColor(Vector4f original) {
        AmbienceModule a = BossUtilityAddon.ambience;
        if (a != null && a.isEnabled() && a.cfgBool("customFog")) {
            int c = a.cfgColor("fogColor");
            float alpha = ((c >> 24) & 0xFF) / 255f;
            return new Vector4f(((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f,
                alpha == 0f ? 1f : alpha);
        }
        return original;
    }
}
