/*
 * This file incorporates movement behavior studied from LiquidBounce (https://github.com/CCBlueX/LiquidBounce).
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

import com.boss.pvp.BossPvpAddon;

import autismclient.api.module.DoubleSetting;
import autismclient.modules.Module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/** Scales one native, validated Riptide impulse; never fabricates a release or dry-land success. */
public final class TridentBoostModule extends Module {
    public TridentBoostModule() {
        super(BossPvpAddon.ID + ":tridentboost", "TridentBoost (Experimental)",
            "Experimental Riptide impulse boost in water/rain only; preserves native charge and weather checks. Server-dependent.");
        add(new DoubleSetting("horizontal", "Horizontal multiplier", 1.25, 1.0, 2.0, 0.05));
        add(new DoubleSetting("vertical", "Vertical multiplier", 1.0, 1.0, 2.0, 0.05)
            .description("Only the launch impulse is scaled, not existing velocity. Generated impulses are capped at 6 blocks/tick."));
    }

    public Vec3 boostImpulse(Minecraft mc, LivingEntity user, Vec3 vanilla) {
        if (!isEnabled() || mc == null || mc.player == null || user != mc.player || mc.level == null
            || user.level() != mc.level || mc.getConnection() == null || mc.gui.screen() != null) return vanilla;
        LocalPlayer p = mc.player;
        if (!p.isAlive() || p.isRemoved() || p.isSpectator() || p.isPassenger() || p.getAbilities().flying
            || p.isFallFlying() || !p.isInWaterOrRain()) return vanilla;
        return scaleImpulse(vanilla, decimal("horizontal"), decimal("vertical"));
    }

    static Vec3 scaleImpulse(Vec3 vanilla, double horizontal, double vertical) {
        double length = Math.hypot(Math.hypot(vanilla.x, vanilla.y), vanilla.z);
        if (!Double.isFinite(length) || length == 0.0 || length >= 6.0) return vanilla;
        double limit = Math.min(2.0, 6.0 / length);
        double h = Double.isFinite(horizontal) ? Math.clamp(horizontal, 1.0, limit) : 1.0;
        double v = Double.isFinite(vertical) ? Math.clamp(vertical, 1.0, limit) : 1.0;
        return new Vec3(vanilla.x * h, vanilla.y * v, vanilla.z * h);
    }
}
