package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.movement.TridentBoostModule;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

@Mixin(TridentItem.class)
public abstract class BossTridentBoostImpulseMixin {
    @ModifyArgs(method = "releaseUsing(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)Z",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;push(DDD)V"), require = 1, allow = 1)
    private void bossTridentBoost$impulse(Args args, ItemStack stack, Level level, LivingEntity user, int timeLeft) {
        TridentBoostModule module = BossPvpAddon.tridentBoost;
        if (module == null) return;
        Vec3 vanilla = new Vec3((double) args.get(0), (double) args.get(1), (double) args.get(2));
        Vec3 boosted = module.boostImpulse(Minecraft.getInstance(), user, vanilla);
        if (boosted == vanilla) return;
        args.set(0, boosted.x);
        args.set(1, boosted.y);
        args.set(2, boosted.z);
    }
}
