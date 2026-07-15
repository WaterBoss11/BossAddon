package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.automation.FastPlaceModule;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class FastPlaceMixin {

    @Shadow private int rightClickDelay;

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void bosspvp$fastPlace(CallbackInfo ci) {
        FastPlaceModule m = BossPvpAddon.fastPlace;
        if (m == null || !m.isEnabled()) return;

        Minecraft mc = (Minecraft) (Object) this;
        if (mc.player == null) return;
        if (!m.appliesTo(mc.player.getMainHandItem())) return;

        int rate = m.placeRate();
        if (this.rightClickDelay > rate) this.rightClickDelay = rate;
    }
}
