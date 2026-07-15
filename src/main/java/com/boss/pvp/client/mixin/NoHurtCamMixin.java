package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.render.NoHurtCamModule;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class NoHurtCamMixin {

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$noHurtCam(CameraRenderState camera, PoseStack pose, CallbackInfo ci) {
        NoHurtCamModule m = BossPvpAddon.noHurtCam;
        if (m != null && m.isEnabled()) ci.cancel();
    }
}
