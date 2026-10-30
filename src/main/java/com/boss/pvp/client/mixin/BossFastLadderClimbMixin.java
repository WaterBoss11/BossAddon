package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.movement.FastLadderModule;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LivingEntity.class)
public abstract class BossFastLadderClimbMixin {
    @Inject(method = "handleOnClimbable(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;",
        at = @At("RETURN"), cancellable = true, require = 1, allow = 1)
    private void bossFastLadder$climb(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        if (!((Object) this instanceof LocalPlayer player)) return;
        FastLadderModule module = BossPvpAddon.fastLadder;
        if (module == null) return;
        Vec3 vanilla = cir.getReturnValue();
        Vec3 result = module.climbMovement(Minecraft.getInstance(), player, vanilla);
        if (result != vanilla) cir.setReturnValue(result);
    }
}
