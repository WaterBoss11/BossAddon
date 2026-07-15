package com.boss.pvp.module.movement;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.input.HeldSlotManager;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismInventoryHelper;
import autismclient.util.AutismRotationUtil;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class BurrowModule extends Module {

    private static final double PLACE_REACH = 4.5;
    private static final int CONFIRM_TICKS = 3;
    private static final int MAX_RETRIES = 5;
    private static final int MAX_PLACE_PER_TICK = 6;
    private static final double CENTER_EPS_SQ = 0.01;
    private static final double INPUT_EPS = 0.02;
    private static final float LEGIT_EASE = 0.25f;

    private static final int K_FWD = 0, K_BACK = 1, K_LEFT = 2, K_RIGHT = 3;

    private static final Direction[] PLACE_ORDER = {
        Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP
    };

    private long lastPlaceMs = 0L;
    private long tickCounter = 0L;
    private int prevSlot = -1;

    private final boolean[] myKeys = new boolean[4];

    private Vec3 legitAim = null;

    private static final class Pending {
        long sentTick;
        int retries;
    }
    private final Map<BlockPos, Pending> pending = new HashMap<>();

    public BurrowModule() {
        super(BossPvpAddon.ID + ":burrow", "Burrow (Beta)", "Encase yourself in obsidian (ghost-safe, ordered, no-teleport recenter).");

        add(new ChoiceSetting("block", "Block", "Obsidian", "Obsidian", "Crying Obsidian").group("General"));
        add(new BoolSetting("centerFirst", "Recenter on block first", true)
            .description("Nudge velocity to block centre before placing (no teleport). Off = place from where you stand.").group("General"));
        add(new BoolSetting("topCap", "Cap above head", true).group("General"));
        add(new BoolSetting("doubleBurrow", "Double (2-thick walls)", false).group("General"));
        add(new IntSetting("blocksPerTick", "Blocks/tick", 2, 1, 6, 1).group("General"));
        add(new IntSetting("delay", "Delay (ms)", 50, 0, 1000, 10).group("General"));
        add(new BoolSetting("disableWhenDone", "Disable when fully encased", false).group("General"));
        add(new ChoiceSetting("rotationMode", "Rotation", "Silent", "Silent", "Real").group("General"));
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            releaseWalkKeys(mc);
            if (mc.player != null && prevSlot >= 0) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        prevSlot = -1;
        legitAim = null;
        pending.clear();
        lastPlaceMs = 0L;
        HeldSlotManager.clear(this);
    }

    public void tick(Minecraft mc) {
        tickCounter++;
        LocalPlayer me = mc.player;
        Level level = mc.level;
        if (me == null || level == null || mc.gameMode == null || mc.gui.screen() != null) { releaseWalkKeys(mc); return; }

        if (!HeldSlotManager.holds(this) && prevSlot >= 0) {
            releaseWalkKeys(mc);
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            prevSlot = -1;
            return;
        }

        reapConfirmed(level);

        if (pending.isEmpty() && !hasOpenEncase(me, level)) {
            releaseWalkKeys(mc);
            legitAim = null;
            HeldSlotManager.release(this);
            restoreSlot(mc, me);
            if (bool("disableWhenDone")) setEnabled(false);
            return;
        }

        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_BURROW);
        if (!HeldSlotManager.holds(this)) { releaseWalkKeys(mc); return; }

        if (bool("centerFirst")) {
            if (!centerByWalk(mc, me)) return;
        } else {
            releaseWalkKeys(mc);
        }

        Item block = blockItem();
        if (!ensureBlock(mc, me, block)) return;

        long now = System.currentTimeMillis();
        boolean delayOk = now - lastPlaceMs >= PvpUtil.jitterMs(integer("delay"));
        boolean realRot = "Real".equals(choice("rotationMode"));
        int budget = Math.min(integer("blocksPerTick"), MAX_PLACE_PER_TICK);
        int used = 0;

        for (BlockPos cell : encasePositions(me)) {
            if (used >= budget) break;
            if (occupiedByPlayer(me, cell)) continue;
            if (confirmedFull(level, cell)) continue;
            if (!isReplaceable(level.getBlockState(cell))) continue;

            Pending pc = pending.get(cell);
            if (pc != null && tickCounter - pc.sentTick < CONFIRM_TICKS) continue;

            BlockHitResult hit = hitFor(mc, me, level, cell);
            if (hit == null) continue;

            if (pc != null && pc.retries >= MAX_RETRIES) {
                pending.remove(cell);
                continue;
            }
            if (pc == null && !delayOk) continue;

            placeGhostSafe(mc, me, hit, realRot);
            if (pc == null) { pc = new Pending(); pending.put(cell, pc); pc.retries = 0; }
            else pc.retries++;
            pc.sentTick = tickCounter;
            used++;
        }

        if (used > 0) lastPlaceMs = now;

        if (realRot) easeLegitCamera(me);
        else legitAim = null;
    }

    private boolean centerByWalk(Minecraft mc, LocalPlayer me) {
        double cx = Math.floor(me.getX()) + 0.5;
        double cz = Math.floor(me.getZ()) + 0.5;
        double dx = cx - me.getX();
        double dz = cz - me.getZ();
        if (dx * dx + dz * dz <= CENTER_EPS_SQ) { releaseWalkKeys(mc); return true; }

        if (playerSteering(mc)) { releaseWalkKeys(mc); return false; }

        double yawRad = Math.toRadians(me.getYRot());
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        double strafe  = dx * cos + dz * sin;
        double forward = -dx * sin + dz * cos;

        setKey(mc, K_FWD,   forward >  INPUT_EPS);
        setKey(mc, K_BACK,  forward < -INPUT_EPS);
        setKey(mc, K_LEFT,  strafe  >  INPUT_EPS);
        setKey(mc, K_RIGHT, strafe  < -INPUT_EPS);
        return false;
    }

    private boolean playerSteering(Minecraft mc) {
        for (int i = 0; i < 4; i++) if (!myKeys[i] && keyFor(mc, i).isDown()) return true;
        return false;
    }

    private void setKey(Minecraft mc, int i, boolean down) {
        if (myKeys[i] == down) return;
        keyFor(mc, i).setDown(down);
        myKeys[i] = down;
    }

    private void releaseWalkKeys(Minecraft mc) {
        if (mc == null || mc.options == null) return;
        for (int i = 0; i < 4; i++) setKey(mc, i, false);
    }

    private KeyMapping keyFor(Minecraft mc, int i) {
        return switch (i) {
            case K_FWD  -> mc.options.keyUp;
            case K_BACK -> mc.options.keyDown;
            case K_LEFT -> mc.options.keyLeft;
            default     -> mc.options.keyRight;
        };
    }

    private void placeGhostSafe(Minecraft mc, LocalPlayer me, BlockHitResult hit, boolean realRot) {
        PvpUtil.ghostSafePlace(mc, me, hit);
        if (realRot) legitAim = hit.getLocation();
    }

    private void easeLegitCamera(LocalPlayer me) {
        if (legitAim == null) return;
        AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(me);
        AutismRotationUtil.Rotation target = AutismRotationUtil.lookingAt(legitAim, me.getEyePosition());
        AutismRotationUtil.Rotation eased = AutismRotationUtil.interpolate(cur, target, LEGIT_EASE);
        AutismRotationUtil.apply(me, AutismRotationUtil.normalizeToSensitivity(eased, cur), false);
    }

    private void reapConfirmed(Level level) {
        for (Iterator<Map.Entry<BlockPos, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<BlockPos, Pending> e = it.next();
            if (confirmedFull(level, e.getKey()) && tickCounter - e.getValue().sentTick >= CONFIRM_TICKS) {
                it.remove();
            }
        }
    }

    private boolean hasOpenEncase(LocalPlayer me, Level level) {
        for (BlockPos pos : encasePositions(me)) {
            if (occupiedByPlayer(me, pos)) continue;
            if (isReplaceable(level.getBlockState(pos))) return true;
        }
        return false;
    }

    private List<BlockPos> encasePositions(LocalPlayer me) {
        BlockPos feet = me.blockPosition();
        BlockPos head = feet.above();
        List<BlockPos> out = new ArrayList<>();
        for (Direction d : Direction.Plane.HORIZONTAL) out.add(feet.relative(d));
        for (Direction d : Direction.Plane.HORIZONTAL) out.add(head.relative(d));
        if (bool("topCap")) out.add(head.above());
        if (bool("doubleBurrow")) {
            for (Direction d : Direction.Plane.HORIZONTAL) out.add(feet.relative(d, 2));
            for (Direction d : Direction.Plane.HORIZONTAL) out.add(head.relative(d, 2));
        }
        return out;
    }

    private BlockHitResult hitFor(Minecraft mc, LocalPlayer me, Level level, BlockPos cell) {
        Vec3 eyes = me.getEyePosition();
        double reachSq = PLACE_REACH * PLACE_REACH;
        for (Direction dir : PLACE_ORDER) {
            BlockPos n = cell.relative(dir);
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced() || !ns.getFluidState().isEmpty()) continue;
            if (!ns.isCollisionShapeFullBlock(level, n)) continue;
            Direction face = dir.getOpposite();
            Vec3 vec = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (vec.distanceToSqr(eyes) > reachSq) continue;
            if (!PvpUtil.canSee(mc, me, eyes, vec)) continue;
            return new BlockHitResult(vec, face, n, false);
        }
        return null;
    }

    private boolean occupiedByPlayer(LocalPlayer me, BlockPos cell) {
        return me.getBoundingBox().intersects(new AABB(cell));
    }

    private boolean confirmedFull(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return !s.isAir() && !s.canBeReplaced() && s.isCollisionShapeFullBlock(level, pos);
    }

    private boolean isReplaceable(BlockState s) {
        return s.isAir() || s.canBeReplaced();
    }

    private Item blockItem() {
        return "Crying Obsidian".equals(choice("block")) ? Items.CRYING_OBSIDIAN : Items.OBSIDIAN;
    }

    private boolean ensureBlock(Minecraft mc, LocalPlayer me, Item block) {
        if (me.getMainHandItem().is(block)) return true;
        for (int i = 0; i <= 8; i++) {
            if (me.getInventory().getItem(i).is(block)) {
                if (prevSlot < 0) prevSlot = me.getInventory().getSelectedSlot();
                AutismInventoryHelper.selectHotbarSlot(mc, i);
                return false;
            }
        }
        return false;
    }

    private void restoreSlot(Minecraft mc, LocalPlayer me) {
        if (prevSlot >= 0) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        prevSlot = -1;
    }
}
