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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/** Runs before native collision movement, not after the player has already climbed. */
public final class FastLadderModule extends Module {
    public FastLadderModule() {
        super(BossPvpAddon.ID + ":fastladder", "FastLadder", "Faster ladder/vine ascent with input and headroom checks; server-dependent.");
        add(new DoubleSetting("speed", "Ascent speed", 0.2872, 0.2, 0.5, 0.01)
            .description("Blocks per tick. Hold jump or move forward into the ladder; sneak keeps vanilla movement."));
    }

    public Vec3 climbMovement(Minecraft mc, LocalPlayer p, Vec3 vanilla) {
        if (!isEnabled() || mc == null || p == null || p != mc.player || mc.level == null || p.level() != mc.level
            || mc.getConnection() == null || mc.gui.screen() != null || !p.isAlive() || p.isRemoved()
            || p.isSpectator() || p.isPassenger() || p.getAbilities().flying || p.isFallFlying()
            || p.isAutoSpinAttack() || p.isInWater() || p.isInLava()
            || p.isShiftKeyDown() || mc.options.keyShift.isDown() || !p.onClimbable()
            || p.getInBlockState().is(Blocks.SCAFFOLDING)) return vanilla;
        if (!p.input.keyPresses.jump() && !(p.horizontalCollision && p.input.hasForwardImpulse())) return vanilla;
        double y = ascentY(vanilla.y, decimal("speed"));
        if (y == vanilla.y || !mc.level.noCollision(p, p.getBoundingBox().expandTowards(0.0, y, 0.0))) return vanilla;
        return new Vec3(vanilla.x, y, vanilla.z);
    }

    static double ascentY(double current, double configured) {
        if (!Double.isFinite(current) || !Double.isFinite(configured)) return current;
        return Math.max(current, Math.clamp(configured, 0.2, 0.5));
    }
}
