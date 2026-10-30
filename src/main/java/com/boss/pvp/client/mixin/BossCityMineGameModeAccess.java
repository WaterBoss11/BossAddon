package com.boss.pvp.client.mixin;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(MultiPlayerGameMode.class)
public interface BossCityMineGameModeAccess {
    @Accessor("destroyBlockPos")
    BlockPos bossCityMine$destroyPos();
}
