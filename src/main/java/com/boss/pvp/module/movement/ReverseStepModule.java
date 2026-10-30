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

import autismclient.api.module.DoubleSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class ReverseStepModule extends Module {
    private LocalPlayer contextPlayer;
    private Level contextLevel;
    private boolean groundObserved;
    private boolean jumped = true;
    private double groundY;
    private int airborneTicks;

    public ReverseStepModule() {
        super(BossPvpAddon.ID + ":reversestep", "ReverseStep",
            "Experimental bounded velocity descent, not a teleport. Servers may correct it. Yields to enabled AutoClutch.");
        add(new DoubleSetting("maxDrop", "Maximum total descent", 1.5, 0.5, 3, 0.5)
            .description("Measured from the last observed grounded position, not extended on every falling tick."));
        add(new DoubleSetting("speed", "Experimental descent cap", 0.6, 0.15, 1.2, 0.05));
    }

    public void tick(Minecraft mc) {
        if (!isEnabled() || !CoreMovementSupport.ready(mc)) { reset(); return; }
        LocalPlayer player = mc.player;
        if (contextPlayer != player || contextLevel != mc.level) {
            reset();
            contextPlayer = player;
            contextLevel = mc.level;
        }
        if (!CoreMovementSupport.groundState(player) || player.hasEffect(MobEffects.LEVITATION)
            || player.hasEffect(MobEffects.SLOW_FALLING)) { groundObserved = false; jumped = true; return; }
        Vec3 velocity = player.getDeltaMovement();
        if (player.onGround()) {
            groundY = player.getY();
            groundObserved = true;
            airborneTicks = 0;
            jumped = mc.options.keyJump.isDown() || velocity.y > 0;
            return;
        }
        if (mc.options.keyJump.isDown() || velocity.y > 0 || player.getY() > groundY + 0.05) jumped = true;
        airborneTicks++;
        if (!groundObserved || jumped || airborneTicks > 8 || velocity.y >= 0
            || mc.options.keyShift.isDown() || mc.options.keyUse.isDown() || player.isUsingItem()
            || player.fallDistance > decimal("maxDrop")) return;
        if (BossPvpAddon.autoClutch != null && BossPvpAddon.autoClutch.isEnabled()) return;
        double remaining = CoreMovementSupport.dropBudget(decimal("maxDrop"), groundY, player.getY());
        if (remaining <= CoreMovementSupport.EPSILON) return;
        AABB nextColumn = player.getBoundingBox().move(velocity.x, 0, velocity.z);
        double drop = CoreMovementSupport.groundDrop(mc, player, nextColumn, remaining);
        if (!Double.isFinite(drop) || drop <= CoreMovementSupport.EPSILON) return;
        Vec3 sweep = new Vec3(velocity.x, -Math.max(0, drop - CoreMovementSupport.EPSILON), velocity.z);
        if (!CoreMovementSupport.clearPath(mc, player, player.getBoundingBox(), sweep)) return;
        double nextY = CoreMovementSupport.descent(velocity.y, drop, decimal("speed"));
        if (nextY < velocity.y - CoreMovementSupport.EPSILON) player.setDeltaMovement(velocity.x, nextY, velocity.z);
    }

    private void reset() {
        contextPlayer = null;
        contextLevel = null;
        groundObserved = false;
        jumped = true;
        groundY = 0;
        airborneTicks = 0;
    }

    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    @Override public void onGameJoin() { reset(); }
    @Override public void onGameLeft() { reset(); }
}
