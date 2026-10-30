package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.movement.WaterWalkModule;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LiquidBlock.class)
public abstract class BossWaterWalkSurfaceMixin {
    @Inject(method = "getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("RETURN"), cancellable = true, require = 1)
    private void bossWaterWalk$surface(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context,
                                      CallbackInfoReturnable<VoxelShape> cir) {
        WaterWalkModule module = BossPvpAddon.waterWalk;
        if (module == null || !cir.getReturnValue().isEmpty()) return;
        VoxelShape surface = module.surfaceShape(Minecraft.getInstance(), level, state, pos, context);
        if (surface != null) cir.setReturnValue(surface);
    }
}
