package com.boss.utility.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor into vanilla's private {@code MultiPlayerGameMode.destroyDelay} — the 5-tick throttle the client
 * imposes between block-break starts. {@link com.boss.utility.module.world.NukerModule}'s optional
 * "No break cooldown" zeroes it each tick so consecutive breaks aren't gated by that delay (Meteor's Nuker
 * suppresses the same cooldown). Off by default; only touched while the setting is on.
 */
@Mixin(MultiPlayerGameMode.class)
public interface MultiPlayerGameModeAccessor {

    @Accessor("destroyDelay")
    void bossutility$setDestroyDelay(int delay);
}
