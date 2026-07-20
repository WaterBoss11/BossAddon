package com.boss.utility.mixin;

import com.boss.utility.flag.FlagReporter;

import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Catches a crash at the moment its report is filled — the last reliable client-side point before the game
 * dies — and hands it to the flag reporter (which persists it to disk before attempting delivery, since a
 * hard crash may kill the JVM before an HTTP call finishes). {@code require = 0} so a mapping miss can't
 * break the build; a native/OOM crash may bypass it, covered only by the disk dump flushed on next launch.
 */
@Mixin(Minecraft.class)
public abstract class FlagCrashMixin {

    @Inject(method = "fillReport", at = @At("HEAD"), require = 0)
    private void bossutility$onCrash(CrashReport report, CallbackInfoReturnable<CrashReport> cir) {
        FlagReporter.onCrash(report);
    }
}
