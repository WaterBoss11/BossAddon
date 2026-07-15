package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.automation.HitboxModule;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
public abstract class EntityHitboxMixin {

    @Inject(method = "getBoundingBox", at = @At("RETURN"), cancellable = true)
    private void bosspvp$inflate(CallbackInfoReturnable<AABB> cir) {
        HitboxModule hb = BossPvpAddon.hitbox;
        if (hb == null || !hb.isEnabled()) return;

        Entity self = (Entity) (Object) this;
        if (hb.playersOnly() && !(self instanceof Player)) return;

        if (self == net.minecraft.client.Minecraft.getInstance().player) return;

        double h = hb.expand();
        double v = hb.expandV();
        if (h <= 0.0 && v <= 0.0) return;

        if (hb.onlyTargeted()
                && (BossPvpAddon.killAura == null || BossPvpAddon.killAura.currentTarget() != self)) return;

        AABB box = cir.getReturnValue();
        cir.setReturnValue(box.inflate(h, v, h));
    }
}
