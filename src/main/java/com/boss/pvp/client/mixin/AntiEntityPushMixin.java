package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.combat.AntiEntityPushModule;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class AntiEntityPushMixin {

    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$antiPush(double x, double y, double z, CallbackInfo ci) {
        AntiEntityPushModule m = BossPvpAddon.antiEntityPush;
        if (m == null || !m.isEnabled()) return;
        if ((Entity) (Object) this != Minecraft.getInstance().player) return;
        if (m.onlyWhileSurrounded()
                && (BossPvpAddon.surround == null || !BossPvpAddon.surround.isEnabled())) return;
        ci.cancel();
    }
}
