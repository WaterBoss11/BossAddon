/*
 * Adapted from LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2026 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package com.boss.pvp.module.movement;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public final class KeepSprintModule extends Module {
    private LocalPlayer activePlayer;
    private Level activeLevel;
    private long activeTick = Long.MIN_VALUE;

    public KeepSprintModule() {
        super(BossPvpAddon.ID + ":keepsprint", "KeepSprint",
            "Experimental grounded attack-momentum retention, not automatic Sprint. Servers may correct it.");
        add(new IntSetting("retention", "Attack motion retained (%)", 100, 60, 100, 5)
            .description("Only modifies native attack slowdown. Never creates sprint or changes received knockback."));
        add(new BoolSetting("playersOnly", "Player attacks only", true)
            .description("Friends are excluded. Yields to host Criticals, AntiKnockback W-tap/SuperKB, jumps, hurt and item use."));
    }

    public void tick(Minecraft mc) {
        if (!isEnabled() || !CoreMovementSupport.ready(mc)) { reset(); return; }
        activePlayer = mc.player;
        activeLevel = mc.level;
        activeTick = mc.level.getGameTime();
    }

    public boolean retainsAttackSprint(Player self, Entity target) {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || !CoreMovementSupport.ready(mc) || self != mc.player || self != activePlayer
            || mc.level != activeLevel || mc.level.getGameTime() - activeTick > 1
            || target == null || target == self || !target.isAlive() || target.isRemoved()) return false;
        LocalPlayer player = mc.player;
        if (PvpUtil.hostEnabled("kill-aura")
            && Boolean.parseBoolean(PvpUtil.hostSetting("kill-aura", "criticals", "true"))) return false;
        String antiKnockback = BossPvpAddon.ID + ":antiknockback";
        if (PvpUtil.hostEnabled(antiKnockback)
            && (Boolean.parseBoolean(PvpUtil.hostSetting(antiKnockback, "wtap", "false"))
                || Boolean.parseBoolean(PvpUtil.hostSetting(antiKnockback, "superKb", "false")))) return false;
        // Do not re-enable sprint: host critical preparation and manual sprint cancellation keep priority.
        if (!player.isSprinting() || !player.onGround() || !CoreMovementSupport.groundState(player)
            || player.getDeltaMovement().y > 0.0 || player.hurtTime > 0 || player.isUsingItem()
            || mc.options.keyJump.isDown() || mc.options.keyShift.isDown()
            || mc.options.keyDown.isDown() || mc.options.keyUse.isDown()) return false;
        return target instanceof Player other ? !PvpUtil.isProtectedPlayer(mc, other) : !bool("playersOnly");
    }

    public double attackMultiplier(Player self, Entity target, double vanilla) {
        return retainsAttackSprint(self, target) ? integer("retention") / 100.0 : vanilla;
    }

    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    @Override public void onGameJoin() { reset(); }
    @Override public void onGameLeft() { reset(); }

    private void reset() {
        activePlayer = null;
        activeLevel = null;
        activeTick = Long.MIN_VALUE;
    }
}
