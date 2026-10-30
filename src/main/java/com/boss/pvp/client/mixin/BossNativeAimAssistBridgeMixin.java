package com.boss.pvp.client.mixin;

import autismclient.modules.Module;
import com.boss.pvp.util.pvp.NativeAimBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Module.class, remap = false)
public abstract class BossNativeAimAssistBridgeMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void bossaddon$captureNativeAim(CallbackInfo ci) {
        NativeAimBridge.capture((Module) (Object) this);
    }

    @Inject(method = {"value", "settingValue"}, at = @At("HEAD"), cancellable = true)
    private void bossaddon$scopedAimSetting(String key, CallbackInfoReturnable<String> cir) {
        String override = NativeAimBridge.override((Module) (Object) this, key);
        if (override != null) cir.setReturnValue(override);
    }
}
