package com.boss.pvp.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record AutoTestPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AutoTestPayload> TYPE =
        CustomPacketPayload.createType("bosspvp_autotest");

    public static final StreamCodec<RegistryFriendlyByteBuf, AutoTestPayload> CODEC =
        StreamCodec.unit(new AutoTestPayload());

    @Override
    public CustomPacketPayload.Type<AutoTestPayload> type() {
        return TYPE;
    }
}
