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
import autismclient.modules.Module;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Local surface collisions only; never spoofs on-ground packets or changes fall/fire handling. */
public final class WaterWalkModule extends Module {
    public WaterWalkModule() {
        super(BossPvpAddon.ID + ":waterwalk", "WaterWalk (Experimental)",
            "Experimental water-surface collisions; sneak to descend. No lava/fire protection; servers may correct movement.");
        add(new BoolSetting("flowing", "Include flowing surfaces", false)
            .description("Experimental. Waterfalls and submerged water columns are always ignored."));
    }

    public VoxelShape surfaceShape(Minecraft mc, BlockGetter level, BlockState state, BlockPos pos, CollisionContext context) {
        if (!isEnabled() || mc == null || mc.player == null || mc.level == null || level != mc.level
            || mc.getConnection() == null || mc.gui.screen() != null
            || !(context instanceof EntityCollisionContext entityContext) || entityContext.getEntity() != mc.player
            || entityContext.isPlacement() || entityContext.isDescending()) return null;
        LocalPlayer p = mc.player;
        if (p.level() != mc.level || !p.isAlive() || p.isRemoved() || p.isSpectator() || p.isPassenger()
            || p.getAbilities().flying || p.isFallFlying() || p.isAutoSpinAttack()
            || p.isShiftKeyDown() || mc.options.keyShift.isDown() || p.isInWater() || p.isUnderWater()
            || p.isInLava() || p.isOnFire()) return null;
        FluidState fluid = state.getFluidState();
        if (!(state.getBlock() instanceof LiquidBlock) || !fluid.is(FluidTags.WATER)
            || (!bool("flowing") && !fluid.isSource()) || state.getValue(LiquidBlock.LEVEL) >= 8
            || level.getFluidState(pos.above()).is(FluidTags.WATER)) return null;
        double height = fluid.getHeight(level, pos);
        if (!(height > 0.0 && height <= 1.0)
            || !canSupport(p.getBoundingBox().minY, pos.getY() + height, p.getDeltaMovement().y, p.fallDistance)) return null;
        return Shapes.box(0.0, 0.0, 0.0, 1.0, height, 1.0);
    }

    static boolean canSupport(double feet, double surface, double velocityY, double fallDistance) {
        return Double.isFinite(feet) && Double.isFinite(surface) && Double.isFinite(velocityY)
            && Double.isFinite(fallDistance) && feet >= surface - 1.0E-5
            && velocityY >= -0.5 && fallDistance >= 0.0 && fallDistance <= 3.0;
    }
}
