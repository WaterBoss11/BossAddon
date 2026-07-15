package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.movement.NoSlowdownModule;

import net.minecraft.client.player.LocalPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LocalPlayer.class)
public abstract class NoSlowdownMixin {

    @Redirect(method = "modifyInput", require = 0, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;isUsingItem()Z"))
    private boolean bosspvp$noSlow(LocalPlayer self) {
        NoSlowdownModule m = BossPvpAddon.noSlowdown;
        if (m != null && m.isEnabled()) return false;
        return self.isUsingItem();
    }
}
