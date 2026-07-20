package com.boss.utility.module.render;

import com.boss.utility.BossUtilityAddon;

import autismclient.modules.Module;
import autismclient.api.module.IntSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;

/**
 * TimeChanger — override the client-side world time. Studied and rewritten from Meteor Client's
 * TimeChanger (GPL-3.0). Cancels incoming time packets and forces a fixed time-of-day locally via
 * {@code ClientLevel.setTimeFromServer}; restores the real time on disable.
 */
public final class TimeChangerModule extends Module {

    private long realTime = 0;

    public TimeChangerModule() {
        super(BossUtilityAddon.ID + ":time-changer", "TimeChanger", "Sets a custom client-side time of day.");
        add(new IntSetting("time", "Time", 6000, 0, 24000, 100)
            .description("Ticks of day: 0 = dawn, 6000 = noon, 12000 = dusk, 18000 = midnight."));
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) realTime = mc.level.getGameTime();
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) mc.level.setTimeFromServer(realTime);
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (packet instanceof ClientboundSetTimePacket p) {
            realTime = p.gameTime();
            return true; // cancel — we drive the time ourselves
        }
        return false;
    }

    public void tick(Minecraft mc) {
        if (mc.level == null) return;
        // Keep the day counter but pin the time-of-day to the configured value.
        long day = realTime - Math.floorMod(realTime, 24000L);
        mc.level.setTimeFromServer(day + integer("time"));
    }
}
