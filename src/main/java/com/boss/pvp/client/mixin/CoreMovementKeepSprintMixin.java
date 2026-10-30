package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.movement.KeepSprintModule;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(Player.class)
public abstract class CoreMovementKeepSprintMixin {
    // 26.2 javap: causeExtraKnockback offsets 105/109 scale X/Z by 0.6; offset 120 clears sprint.
    @ModifyConstant(method = "causeExtraKnockback(Lnet/minecraft/world/entity/Entity;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/damagesource/DamageSource;FZ)V",
        constant = @Constant(doubleValue = 0.6), require = 2, expect = 2, allow = 2)
    private double bosspvp$coreAttackMomentum(double vanilla, Entity target, float knockback,
                                             Vec3 targetVelocity, DamageSource source, float power, boolean force) {
        KeepSprintModule module = BossPvpAddon.keepSprint;
        return module == null ? vanilla : module.attackMultiplier((Player) (Object) this, target, vanilla);
    }

    @Redirect(method = "causeExtraKnockback(Lnet/minecraft/world/entity/Entity;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/damagesource/DamageSource;FZ)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;setSprinting(Z)V"),
        require = 1, expect = 1, allow = 1)
    private void bosspvp$coreAttackSprint(Player player, boolean sprinting, Entity target, float knockback,
                                         Vec3 targetVelocity, DamageSource source, float power, boolean force) {
        KeepSprintModule module = BossPvpAddon.keepSprint;
        if (!sprinting && module != null && module.retainsAttackSprint(player, target)) return;
        player.setSprinting(sprinting);
    }
}
