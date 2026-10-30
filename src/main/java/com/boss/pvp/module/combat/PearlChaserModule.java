package com.boss.pvp.module.combat;

import autismclient.api.module.DoubleSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.ProjectileMath;
import com.boss.pvp.util.pvp.PvpUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** ThunderHack PearlChaser behavior adapted under GPL-3.0; unknown pearl owners are never guessed. */
public final class PearlChaserModule extends Module {
    private static final int MAX_PREDICTION_TICKS = 60;
    private final Map<Integer, Long> followed = new HashMap<>();
    private Level world;
    private long lastThrow = Long.MIN_VALUE;

    public PearlChaserModule() {
        super(BossPvpAddon.ID + ":pearlchaser", "PearlChaser", "Follows identified enemy pearls to clear, supported landing positions.");
        add(new DoubleSetting("range", "Pearl search range", 48.0, 8.0, 128.0, 1.0));
        add(new DoubleSetting("minHealth", "Minimum health", 12.0, 5.0, 40.0, 0.5));
        add(new DoubleSetting("minDistance", "Minimum chase distance", 7.0, 3.0, 32.0, 0.5));
        add(new IntSetting("delay", "Ticks between throws", 20, 20, 100, 1));
    }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null
                || !mc.player.isAlive() || mc.player.isUsingItem() || PvpUtil.hostEnabled("hide")) return;
        if (world != mc.level) { reset(); world = mc.level; }
        long now = mc.level.getGameTime();
        followed.entrySet().removeIf(entry -> now >= entry.getValue() || mc.level.getEntity(entry.getKey()) == null);
        if (followed.size() >= 64 || lastThrow != Long.MIN_VALUE && now >= lastThrow && now - lastThrow < integer("delay")) return;
        if (!RangedCombatDecisions.healthy(mc.player.getHealth() + mc.player.getAbsorptionAmount(), decimal("minHealth"))) return;
        double rangeSquared = decimal("range") * decimal("range");
        int considered = 0;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ThrownEnderpearl pearl) || pearl.isRemoved() || followed.containsKey(pearl.getId())
                    || mc.player.distanceToSqr(pearl) > rangeSquared) continue;
            if (++considered > 32) break;
            if (!(pearl.getOwner() instanceof Player owner) || !owner.isAlive() || PvpUtil.isProtectedPlayer(mc, owner)) continue;
            var landing = ProjectileMath.trace(mc.level, pearl, ProjectileMath.Kind.THROWABLE,
                pearl.position(), pearl.getDeltaMovement(), MAX_PREDICTION_TICKS);
            if (landing == null || !(landing.hit() instanceof BlockHitResult hit) || !safeLanding(mc, hit)) continue;
            Vec3 destination = hit.getLocation();
            if (mc.player.position().distanceToSqr(destination) < decimal("minDistance") * decimal("minDistance")) continue;
            Vec3 origin = mc.player.getEyePosition().add(0.0, -0.1, 0.0);
            Vec3 velocity = mc.player.getDeltaMovement();
            Vec3 inherited = new Vec3(velocity.x, mc.player.onGround() ? 0.0 : velocity.y, velocity.z);
            var shot = ProjectileMath.solve(ProjectileMath.Kind.THROWABLE, origin, inherited, destination, Vec3.ZERO, MAX_PREDICTION_TICKS);
            if (shot == null) continue;
            var path = ProjectileMath.trace(mc.level, mc.player, ProjectileMath.Kind.THROWABLE,
                origin, shot.velocity(), MAX_PREDICTION_TICKS);
            if (path == null || !(path.hit() instanceof BlockHitResult ourHit)
                    || ourHit.getLocation().distanceToSqr(destination) > 2.25 || !safeLanding(mc, ourHit)) continue;
            if (PvpUtil.useHotbarItem(mc, this, 70, Items.ENDER_PEARL, shot.yaw(), shot.pitch())) {
                followed.put(pearl.getId(), now + 100);
                lastThrow = now;
                return;
            }
        }
    }

    private boolean safeLanding(Minecraft mc, BlockHitResult hit) {
        if (hit.getDirection() != Direction.UP) return false;
        BlockPos support = hit.getBlockPos();
        BlockPos feet = support.above();
        if (!mc.level.hasChunkAt(feet) || !mc.level.hasChunkAt(feet.above())) return false;
        for (BlockPos position : new BlockPos[]{support, feet, feet.above()}) {
            var state = mc.level.getBlockState(position);
            if (!state.getFluidState().isEmpty() || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                    || state.is(Blocks.CACTUS) || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CAMPFIRE)
                    || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)) return false;
        }
        Vec3 landing = new Vec3(hit.getLocation().x, feet.getY() + 0.01, hit.getLocation().z);
        return !mc.level.getBlockState(support).getCollisionShape(mc.level, support).isEmpty()
            && mc.level.noCollision(mc.player, mc.player.getBoundingBox().move(landing.subtract(mc.player.position())));
    }

    private void reset() {
        followed.clear();
        world = null;
        lastThrow = Long.MIN_VALUE;
        HeldSlotManager.clear(this);
    }

    @Override public void onDisable() { reset(); }
    @Override public void onGameLeft() { reset(); }
    @Override public void onGameJoin() { reset(); }
}
