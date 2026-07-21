package com.boss.pvp.client.mixin;

import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Read-only accessor for the (protected) raw {@link Connection} held by every client packet listener, so the
 * reconfigure guard can close it. Exposes nothing that mutates or sends — it only hands back the existing
 * connection object.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public interface CommonListenerConnectionAccessor {

    @Accessor("connection")
    Connection bosspvp$connection();
}
