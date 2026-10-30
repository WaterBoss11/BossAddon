package com.boss.pvp.client.mixin;

import com.boss.pvp.BossPvpAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class BossSpecializedCombatPacketMixin {
    @Inject(method = "handleBlockUpdate", at = @At("TAIL"))
    private void bossSpecializedCombat$block(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().getConnection()) return;
        bossSpecializedCombat$confirm(packet.getPos(), packet.getBlockState());
    }

    @Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
    private void bossSpecializedCombat$section(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().getConnection()) return;
        packet.runUpdates(BossSpecializedCombatPacketMixin::bossSpecializedCombat$confirm);
    }

    @Inject(method = "handleMovePlayer", at = @At("TAIL"))
    private void bossSpecializedCombat$correction(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        if ((Object) this != Minecraft.getInstance().getConnection()) return;
        if (BossPvpAddon.spearAssist != null) BossPvpAddon.spearAssist.onServerCorrection();
    }

    private static void bossSpecializedCombat$confirm(BlockPos pos, BlockState state) {
        if (BossPvpAddon.pistonPush != null) BossPvpAddon.pistonPush.onServerBlockUpdate(pos, state);
        if (BossPvpAddon.cityMine != null) BossPvpAddon.cityMine.onServerBlockUpdate(pos, state);
    }
}
