package com.boss.pvp.module.movement;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class HoleSnapModule extends Module {
    private LocalPlayer contextPlayer;
    private Level contextLevel;
    private BlockPos hole;
    private boolean armed;
    private boolean controlling;
    private int ticks;
    private int stalledTicks;
    private double previousDistance = Double.POSITIVE_INFINITY;
    private double startY;
    private long controlTick = Long.MIN_VALUE;

    public HoleSnapModule() {
        super(BossPvpAddon.ID + ":holesnap", "HoleSnap",
            "One bounded, straight-line snap into a clear single hole. Movement keys cancel; no timers or host toggles.");
        add(new DoubleSetting("range", "Search range", 4, 1, 6, 0.5));
        add(new IntSetting("maxDrop", "Maximum drop", 2, 1, 3, 1));
        add(new DoubleSetting("speed", "Steering speed cap", 0.18, 0.05, 0.3, 0.01));
        add(new IntSetting("timeout", "Cancel after (ticks)", 60, 10, 120, 5));
        add(new BoolSetting("resistantOnly", "Obsidian/bedrock holes only", true));
    }

    public void tick(Minecraft mc) {
        controlling = false;
        if (!isEnabled() || !armed) return;
        if (!CoreMovementSupport.ready(mc)) {
            if (contextPlayer != null) finish();
            return;
        }
        LocalPlayer player = mc.player;
        if (contextPlayer != null && (contextPlayer != player || contextLevel != mc.level)) { finish(); return; }
        if (contextPlayer == null) {
            contextPlayer = player;
            contextLevel = mc.level;
            startY = player.getY();
        }
        if (!CoreMovementSupport.groundState(player) || manualOverride(mc) || player.isUsingItem()
            || player.getDeltaMovement().y > 0.1 || player.fallDistance > integer("maxDrop")
            || ++ticks > integer("timeout")) { finish(); return; }
        if (hole == null) hole = nearestHole(mc, player);
        if (hole == null || !validHole(mc, player, hole)) { finish(); return; }
        double dx = hole.getX() + 0.5 - player.getX();
        double dy = hole.getY() - player.getY();
        double dz = hole.getZ() + 0.5 - player.getZ();
        if (CoreMovementSupport.arrived(dx, dy, dz) && player.onGround()) {
            player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
            finish();
            return;
        }
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance >= previousDistance - 0.005) stalledTicks++; else stalledTicks = 0;
        previousDistance = distance;
        if (stalledTicks >= 8 || player.horizontalCollision) { finish(); return; }
        CoreMovementSupport.Horizontal step = CoreMovementSupport.toward(dx, dz, decimal("speed"));
        Vec3 movement = new Vec3(step.x(), 0, step.z());
        double remainingDrop = CoreMovementSupport.dropBudget(integer("maxDrop"), startY, player.getY());
        if (remainingDrop < 0 || !CoreMovementSupport.clearPath(mc, player, player.getBoundingBox(), movement)
            || !Double.isFinite(CoreMovementSupport.groundDrop(mc, player,
                player.getBoundingBox().move(movement), remainingDrop))) { finish(); return; }
        player.setDeltaMovement(step.x(), player.getDeltaMovement().y, step.z());
        controlling = true;
        controlTick = mc.level.getGameTime();
    }

    private BlockPos nearestHole(Minecraft mc, LocalPlayer player) {
        int radius = (int) Math.ceil(decimal("range"));
        BlockPos origin = player.blockPosition();
        BlockPos nearest = null;
        double best = decimal("range") * decimal("range");
        // ponytail: bounded single-hole straight-line search; a pathfinder is needed for routes around walls.
        for (int y = 0; y <= integer("maxDrop"); y++) {
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos candidate = origin.offset(x, -y, z);
                    if (candidate.getY() > player.getY() + CoreMovementSupport.EPSILON) continue;
                    double distance = player.position().distanceToSqr(
                        new Vec3(candidate.getX() + 0.5, candidate.getY(), candidate.getZ() + 0.5));
                    if (distance < best && validHole(mc, player, candidate)) {
                        best = distance;
                        nearest = candidate;
                    }
                }
            }
        }
        return nearest;
    }

    private boolean validHole(Minecraft mc, LocalPlayer player, BlockPos pos) {
        AABB body = player.getBoundingBox().move(pos.getX() + 0.5 - player.getX(),
            pos.getY() - player.getY(), pos.getZ() + 0.5 - player.getZ());
        if (!CoreMovementSupport.loaded(mc.level, body.inflate(1, 1, 1))
            || !mc.level.noCollision(player, body.deflate(CoreMovementSupport.EPSILON))
            || CoreMovementSupport.occupied(mc, player, body) || CoreMovementSupport.hazards(mc.level, body)
            || !wall(mc.level, pos.below())) return false;
        for (Direction direction : Direction.Plane.HORIZONTAL) if (!wall(mc.level, pos.relative(direction))) return false;
        return Double.isFinite(CoreMovementSupport.groundDrop(mc, player, body, 0.05));
    }

    private boolean wall(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (CoreMovementSupport.hazardous(state) || !state.isCollisionShapeFullBlock(level, pos)) return false;
        return !bool("resistantOnly") || state.is(Blocks.BEDROCK) || state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN);
    }

    private boolean manualOverride(Minecraft mc) {
        return CoreMovementSupport.horizontalInput(mc) || mc.options.keyJump.isDown() || mc.options.keyShift.isDown();
    }

    public boolean controlsMovement() {
        Minecraft mc = Minecraft.getInstance();
        return isEnabled() && controlling && CoreMovementSupport.ready(mc) && mc.player == contextPlayer
            && mc.level == contextLevel && CoreMovementSupport.groundState(mc.player) && !manualOverride(mc)
            && !mc.player.isUsingItem() && mc.level.getGameTime() - controlTick <= 1;
    }

    private void finish() {
        if (isEnabled()) setEnabled(false); else reset();
    }

    private void reset() {
        controlling = false;
        armed = false;
        hole = null;
        contextPlayer = null;
        contextLevel = null;
        ticks = 0;
        stalledTicks = 0;
        previousDistance = Double.POSITIVE_INFINITY;
        controlTick = Long.MIN_VALUE;
    }

    @Override public void onEnable() { reset(); armed = true; }
    @Override public void onDisable() { reset(); }
    @Override public void onGameJoin() { reset(); }
    @Override public void onGameLeft() { reset(); }
}
