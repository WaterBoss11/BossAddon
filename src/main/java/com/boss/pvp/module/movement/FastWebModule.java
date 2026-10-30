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

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ChoiceSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.modules.Module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Stateless cobweb hook: no keys, timers, attributes, or other modules are claimed. */
public final class FastWebModule extends Module {
    public FastWebModule() {
        super(BossPvpAddon.ID + ":fastweb", "FastWeb", "Bounded cobweb slowdown reduction or sneaking escape; server-dependent.");
        add(new ChoiceSetting("mode", "Mode", "Reduce", "Reduce", "NoWeb", "Escape")
            .description("NoWeb removes local web drag. Escape also descends while sneaking; servers may correct either mode."));
        add(new DoubleSetting("horizontal", "Horizontal retention", 0.75, 0.25, 1.0, 0.05)
            .description("Reduce mode only. Never weakens vanilla Weaving protection."));
        add(new DoubleSetting("vertical", "Vertical retention", 0.25, 0.05, 1.0, 0.05)
            .description("Reduce mode only. Never retains less vertical movement than vanilla."));
        add(new DoubleSetting("escapeSpeed", "Escape descent", 0.3, 0.1, 0.5, 0.05)
            .description("Escape mode only; blocks per tick while sneaking, not jumping."));
        add(new BoolSetting("sneakOnly", "Only while sneaking", false));
    }

    public void applyWebSlowdown(Entity entity, BlockState state, Vec3 vanilla) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc == null ? null : mc.player;
        Vec3 slowdown = vanilla;
        if (isEnabled() && p != null && entity == p && mc.level != null && p.level() == mc.level
            && mc.getConnection() != null && mc.gui.screen() == null && p.isAlive() && !p.isRemoved()
            && !p.isSpectator() && !p.isPassenger() && !p.getAbilities().flying && !p.isFallFlying()
            && !p.isAutoSpinAttack() && !p.isInWater() && !p.isInLava()) {
            boolean sneaking = p.isShiftKeyDown() || mc.options.keyShift.isDown();
            if (!bool("sneakOnly") || sneaking) {
                String mode = choice("mode");
                if ("NoWeb".equals(mode) || "Escape".equals(mode)) {
                    // Entity.move treats ZERO as no stuck multiplier; keep the native web fall-reset call.
                    slowdown = Vec3.ZERO;
                    if ("Escape".equals(mode) && sneaking && !p.onGround()
                        && !p.input.keyPresses.jump() && !mc.options.keyJump.isDown()) {
                        Vec3 movement = p.getDeltaMovement();
                        double y = escapeY(movement.y, decimal("escapeSpeed"));
                        if (y != movement.y) p.setDeltaMovement(movement.x, y, movement.z);
                    }
                } else {
                    slowdown = new Vec3(retention(vanilla.x, decimal("horizontal")),
                        retention(vanilla.y, decimal("vertical")), retention(vanilla.z, decimal("horizontal")));
                }
            }
        }
        entity.makeStuckInBlock(state, slowdown);
    }

    static double retention(double vanilla, double configured) {
        return Double.isFinite(configured) ? Math.clamp(configured, vanilla, 1.0) : vanilla;
    }

    static double escapeY(double current, double configured) {
        if (!Double.isFinite(current) || !Double.isFinite(configured) || current > 0.0) return current;
        return Math.min(current, -Math.clamp(configured, 0.1, 0.5));
    }
}
