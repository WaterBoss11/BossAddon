package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LocalPlayer.class)
public abstract class BossSpearAssistMovementMixin {
    @ModifyVariable(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Vec3 bossSpearAssist$movement(Vec3 value, MoverType type, Vec3 original) {
        var module = BossPvpAddon.spearAssist;
        Minecraft mc = Minecraft.getInstance();
        return module != null && (Object) this == mc.player ? module.additionalMovement(mc, type, value) : value;
    }
}
