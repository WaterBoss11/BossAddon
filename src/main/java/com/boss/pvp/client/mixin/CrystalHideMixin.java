package com.boss.pvp.client.mixin;

import com.boss.pvp.util.pvp.CrystalHideManager;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Returns a zero-size bounding box for any end crystal AutoCrystal has hidden ({@link CrystalHideManager}),
 * so it drops out of the client's entity raytrace/pick the moment we register a break — HCsCR's core trick,
 * studied and rewritten (Apache-2.0). An empty box is enough: raytrace can't hit a degenerate AABB, so the
 * spot reads clear for the next crystal without the hit round-tripping through the server first. When the
 * resync window elapses (or the server confirms the kill) the id leaves the hidden set and the real box
 * returns on the next call.
 */
@Mixin(Entity.class)
public abstract class CrystalHideMixin {

    @Inject(method = "getBoundingBox", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$hideCrystal(CallbackInfoReturnable<AABB> cir) {
        Entity self = (Entity) (Object) this;
        if (!(self instanceof EndCrystal) || !CrystalHideManager.isHidden(self.getId())) return;
        Vec3 p = self.position();
        cir.setReturnValue(new AABB(p.x, p.y, p.z, p.x, p.y, p.z));
    }
}
