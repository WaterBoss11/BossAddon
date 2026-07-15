package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;

import autismclient.modules.Module;
import autismclient.api.module.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

public final class AutoLeaveModule extends Module {

    private static final double COMBAT_RANGE = 16.0;
    private static final long COMBAT_DAMAGE_WINDOW_MS = 3000L;

    private int prevHurtTime = 0;
    private long lastDamageMs = 0L;
    private boolean left = false;

    public AutoLeaveModule() {
        super(BossPvpAddon.ID + ":autoleave", "AutoLeave", "Disconnect when your health drops below a threshold.");
        add(new DoubleSetting("healthThreshold", "Health threshold", 4.0, 1.0, 10.0, 0.5)
            .description("Leave when health (HP, not hearts — 4 HP = 2 hearts) drops to/below this.").group("General"));
        add(new BoolSetting("onlyInCombat", "Only in combat", true)
            .description("Only leave if an enemy is within 16 blocks or you took damage in the last 3s.").group("General"));
    }

    @Override
    public void onDisable() {
        prevHurtTime = 0;
        lastDamageMs = 0L;
        left = false;
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { prevHurtTime = 0; left = false; return; }
        if (left) return;

        long now = System.currentTimeMillis();

        if (p.hurtTime > prevHurtTime) lastDamageMs = now;
        prevHurtTime = p.hurtTime;

        if (p.getHealth() > (float) decimal("healthThreshold")) return;
        if (bool("onlyInCombat") && !inCombat(mc, p, now)) return;

        if (mc.getConnection() != null) {
            mc.getConnection().getConnection().disconnect(Component.literal("[Boss's PVP] AutoLeave: low health"));
            left = true;
        }
    }

    private boolean inCombat(Minecraft mc, LocalPlayer p, long now) {
        if (now - lastDamageMs <= COMBAT_DAMAGE_WINDOW_MS) return true;
        double r2 = COMBAT_RANGE * COMBAT_RANGE;
        for (Player pl : mc.level.players()) {
            if (pl == p || pl.isSpectator()) continue;
            if (pl.distanceToSqr(p) <= r2) return true;
        }
        return false;
    }
}
