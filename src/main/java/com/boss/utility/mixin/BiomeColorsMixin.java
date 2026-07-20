package com.boss.utility.mixin;

import com.boss.utility.BossUtilityAddon;
import com.boss.utility.module.render.AmbienceModule;

import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Ambience — custom grass / foliage / water biome tint. Studied from Meteor Client's BiomeColorsMixin
 * (GPL-3.0). Overrides the biome average-colour lookups with the module's configured colours.
 */
@Mixin(BiomeColors.class)
public class BiomeColorsMixin {

    @Inject(method = "getAverageGrassColor", at = @At("HEAD"), cancellable = true)
    private static void bossutility$grass(BlockAndTintGetter world, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        AmbienceModule a = BossUtilityAddon.ambience;
        if (a != null && a.isEnabled() && a.cfgBool("customGrass")) cir.setReturnValue(a.cfgColor("grassColor") & 0xFFFFFF);
    }

    @Inject(method = "getAverageFoliageColor", at = @At("HEAD"), cancellable = true)
    private static void bossutility$foliage(BlockAndTintGetter world, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        AmbienceModule a = BossUtilityAddon.ambience;
        if (a != null && a.isEnabled() && a.cfgBool("customFoliage")) cir.setReturnValue(a.cfgColor("foliageColor") & 0xFFFFFF);
    }

    @Inject(method = "getAverageWaterColor", at = @At("HEAD"), cancellable = true)
    private static void bossutility$water(BlockAndTintGetter world, BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        AmbienceModule a = BossUtilityAddon.ambience;
        if (a != null && a.isEnabled() && a.cfgBool("customWater")) cir.setReturnValue(a.cfgColor("waterColor") & 0xFFFFFF);
    }
}
