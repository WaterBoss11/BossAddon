package com.boss.pvp.relay;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * Programmatic "join this server" — the exact same client action as clicking <b>Join Server</b> or
 * double-clicking a server-list entry.
 *
 * <p>This uses the CLEAN vanilla path only: {@link ConnectScreen#startConnecting} is {@code public static} and
 * is precisely what vanilla itself calls from the multiplayer screen (AUTISM mixes into the very same method).
 * No mixin, no editing {@code servers.dat}, no faking a click sequence — just the real join entry point.
 *
 * <p><b>Consent:</b> nothing here ever runs automatically. The only caller is the party-warp flow in
 * {@link RelayManager}, and it only calls {@link #connectTo} AFTER the local user has explicitly typed
 * {@code ?bossaddon party warp accept} for a request whose destination address they were shown.
 */
public final class ServerConnect {

    private ServerConnect() {}

    /** True if {@code address} is a well-formed server address vanilla would accept (host, optional :port). */
    public static boolean isValid(String address) {
        return address != null && !address.isBlank() && ServerAddress.isValidAddress(address.trim());
    }

    /**
     * Leave the current server (if any) and connect to {@code address}, marshalled onto the render thread.
     * No-op if the address is malformed. The caller owns user consent — this performs the connect once called.
     */
    public static void connectTo(String address) {
        if (!isValid(address)) return;
        final String addr = address.trim();
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                ServerAddress parsed = ServerAddress.parseString(addr);
                ServerData data = new ServerData("BossAddon Party Warp", addr, ServerData.Type.OTHER);
                Screen parent = new TitleScreen();   // fallback screen if the connect is aborted/fails
                if (mc.level != null) {
                    // Cleanly leave the current server before opening a second connection.
                    mc.disconnect(parent, false);
                }
                ConnectScreen.startConnecting(parent, mc, parsed, data, false, null);
            } catch (Throwable t) {
                System.out.println("[boss-pvp/warp] connect to '" + addr + "' failed: " + t);
            }
        });
    }
}
