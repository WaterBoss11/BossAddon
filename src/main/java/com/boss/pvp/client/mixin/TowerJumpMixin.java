package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.movement.ScaffoldModule;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class TowerJumpMixin {

    @Inject(method = "getJumpPower()F", at = @At("RETURN"), cancellable = true)
    private void bosspvp$towerJumpPower(CallbackInfoReturnable<Float> cir) {
        if (!((Object) this instanceof LocalPlayer self)) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || self != mc.player) return;
        ScaffoldModule s = BossPvpAddon.scaffold;
        if (s != null && s.towering()) cir.setReturnValue(s.towerLaunch());
    }
}
