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
        Entity self = (Entity) (Object) this;
        if (self != Minecraft.getInstance().player) return;
        if (m.onlyWhileSurrounded()
                && (BossPvpAddon.surround == null || !BossPvpAddon.surround.isEnabled())) return;

        // Replace the vanilla push with the mode's result. Either way the original full push is cancelled;
        // in Modify mode the scaled remainder is re-applied (vanilla push just adds to deltaMovement, so this
        // reproduces it faithfully at reduced strength). Cancel mode -> zero delta -> unchanged full-block.
        double[] d = m.pushDelta(x, y, z);
        if (d[0] != 0.0 || d[1] != 0.0 || d[2] != 0.0) {
            self.setDeltaMovement(self.getDeltaMovement().add(d[0], d[1], d[2]));
        }
        ci.cancel();
    }
}
