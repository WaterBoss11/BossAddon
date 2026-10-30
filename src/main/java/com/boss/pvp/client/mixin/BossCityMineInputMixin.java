package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public abstract class BossCityMineInputMixin {
    @Inject(method = "startAttack", at = @At("HEAD"))
    private void bossCityMine$manualStart(CallbackInfoReturnable<Boolean> cir) {
        if (BossPvpAddon.cityMine != null) BossPvpAddon.cityMine.yieldToManualInput((Minecraft) (Object) this);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"))
    private void bossCityMine$manualContinue(boolean attack, CallbackInfo ci) {
        if (attack && BossPvpAddon.cityMine != null) BossPvpAddon.cityMine.yieldToManualInput((Minecraft) (Object) this);
    }

    @Redirect(method = "continueAttack", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;stopDestroyBlock()V"))
    private void bossCityMine$keepOwnedProgress(MultiPlayerGameMode gameMode) {
        if (BossPvpAddon.cityMine == null || !BossPvpAddon.cityMine.keepNativeMining((Minecraft) (Object) this)) {
            gameMode.stopDestroyBlock();
        }
    }
}
