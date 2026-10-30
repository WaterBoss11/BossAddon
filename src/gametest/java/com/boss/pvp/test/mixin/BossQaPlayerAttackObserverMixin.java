package com.boss.pvp.test.mixin;

import com.boss.pvp.test.BossQaDamageObserver;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Measures real vanilla server Player.attack before/after; never changes arguments, attributes or damage. */
@Mixin(Player.class)
public abstract class BossQaPlayerAttackObserverMixin {
    @Inject(method = "attack(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), require = 1)
    private void bossQa$nativeAttackHead(Entity target, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) BossQaDamageObserver.attackHead(player, target);
    }

    @Inject(method = "attack(Lnet/minecraft/world/entity/Entity;)V", at = @At("RETURN"), require = 1)
    private void bossQa$nativeAttackReturn(Entity target, CallbackInfo ci) {
        if ((Object) this instanceof ServerPlayer player) BossQaDamageObserver.attackReturn(player, target);
    }
}
