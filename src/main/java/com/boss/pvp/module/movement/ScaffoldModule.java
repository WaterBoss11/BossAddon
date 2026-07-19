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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Scaffold — auto-bridge rebuilt around LiquidBounce's nextgen technique set, adapted to Java + AUTISM 3.4.
 * Keeps the ghost-safe placement path (PvpUtil.ghostSafeUseOn), HeldSlotManager slot arbitration, and the
 * velocity-lookahead placement search; adds Zitter, Eagle, StabilizeMovement, AdvancedRotation, the
 * None/Safe/OnEdge SafeWalk set, an improved Motion tower, Down scaffold, Slow (speed limiter) and
 * SimulatePlacementAttempts.
 */
public final class ScaffoldModule extends Module {

    private static final double PLACE_REACH = 4.5;
    private static final double SPEED_FACTOR = 8.0;
    private static final double MAX_LOOKAHEAD = 4.0;
    private static final long RETRY_MS = 150L;
    private static final int MAX_RETRIES = 5;
    private static final double FACE_JITTER = 0.15;
    // Vanilla-legit placement ceiling: at most ONE block placed per client tick (20/s), the same rate a
    // player holding right-click produces. Bursting several placements in a single tick under fast movement
    // is exactly what trips Paper's packet-per-tick threshold and NCP/Grim block-place checks — the cause of
    // the "too many packets too fast" kicks. Do not raise this above 1: keep the per-tick cap regardless of
    // movement speed. (Tower places its single below-block per tick and is gated the same way.)
    private static final int MAX_PLACE_PER_TICK = 1;

    private static final double CENTER_EPS_SQ = 0.01;
    private static final double INPUT_EPS = 0.02;
    private static final double VEL_EPS_SQ = 0.0016;
    private static final int K_FWD = 0, K_BACK = 1, K_LEFT = 2, K_RIGHT = 3;

    private long lastPlaceMs = 0L;
    private int prevSlot = -1;
    private boolean sneaking = false;
    private final boolean[] myKeys = new boolean[4];
    private final java.util.Random rng = new java.util.Random();

    private BlockPos lastTarget = null;
    private Hit lastHit = null;
    private long lastSentMs = 0L;
    private int retries = 0;

    private boolean towerActive = false;
    private boolean towerCentered = false;
    private int towerFloorY = Integer.MIN_VALUE;
    private double jumpOffY = Double.NaN;

    // Zitter state.
    private boolean zitterSide = false;
    private long zitterFlipMs = 0L;
    // Eagle state.
    private int placedThisCycle = 0;

    private record Hit(BlockPos neighbor, Direction face, Vec3 vec) {}

    public ScaffoldModule() {
        super(BossPvpAddon.ID + ":scaffold", "Scaffold", "Automatically places blocks under you as you walk, so you can bridge without looking down.");

        add(new ChoiceSetting("mode", "Mode", "Normal", "Normal", "Fast", "Legit").group("General"));
        add(new BoolSetting("silentRotation", "Silent rotation", true)
            .description("On: your camera never visibly turns toward placements. Off: in Legit mode the camera really turns.").group("General"));
        add(new DoubleSetting("lookahead", "Place ahead (ticks)", 1.5, 0.0, 4.0, 0.1).group("General"));
        add(new BoolSetting("keepY", "Stay at same height", true)
            .description("Keeps the bridge flat at one height instead of following where you look.").group("General"));
        add(new BoolSetting("down", "Bridge downward", false)
            .description("Places blocks one level lower so you descend as you walk.").group("General"));
        add(new BoolSetting("speedScaling", "Place further when moving fast", true).group("General"));
        add(new ChoiceSetting("width", "Bridge width", "1", "1", "3").group("General"));
        add(new BoolSetting("airPlace", "Place in mid-air (no support needed)", false).group("General"));

        add(new ChoiceSetting("safewalk", "Edge protection (SafeWalk)", "OnEdge", "None", "Safe", "OnEdge").group("Movement"));
        add(new ChoiceSetting("zitter", "Zitter (side-to-side wiggle)", "Off", "Off", "Teleport", "Smooth").group("Movement"));
        add(new BoolSetting("eagle", "Auto-sneak at edges (Eagle)", false).group("Movement"));
        add(new BoolSetting("stabilize", "Keep bridge straight", true)
            .description("Removes sideways drift so you bridge in a straight line (turned off while Zitter is on).").group("Movement"));
        add(new BoolSetting("slow", "Limit walking speed", false).group("Movement"));
        add(new DoubleSetting("speedLimit", "Speed limit", 0.11, 0.01, 0.40, 0.01).group("Movement"));

        add(new BoolSetting("advancedRotation", "Randomize aim point", false).group("Rotation"));
        add(new DoubleSetting("rangeX", "Randomness: X", 0.15, 0.0, 0.5, 0.01).group("Rotation"));
        add(new DoubleSetting("rangeY", "Randomness: Y", 0.15, 0.0, 0.5, 0.01).group("Rotation"));
        add(new DoubleSetting("rangeZ", "Randomness: Z", 0.15, 0.0, 0.5, 0.01).group("Rotation"));
        add(new BoolSetting("simulatePlacements", "Fake swings on failed placements", false)
            .description("Swings your arm when a block can't be placed, so it looks more natural to others.").group("Rotation"));

        add(new BoolSetting("tower", "Tower up (hold jump)", true).group("Tower"));
        add(new DoubleSetting("towerSpeed", "Tower climb speed", 0.5, 0.1, 1.0, 0.01).group("Tower"));
        add(new BoolSetting("whileMoving", "Tower while moving", false)
            .description("Off = only tower when standing still (recommended).").group("Tower"));
        add(new BoolSetting("centerFirst", "Center on block before towering", true).group("Tower"));

        add(new BoolSetting("preferObsidian", "Prefer obsidian", true).group("Blocks"));
        add(new BoolSetting("allowNonFull", "Allow partial blocks (slabs etc.)", false).group("Blocks"));
        add(new BoolSetting("switchBack", "Switch back to previous item", true).group("Blocks"));
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            if (mc.player != null && prevSlot >= 0 && bool("switchBack")) {
                AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            }
            setSneak(mc, false);
            releaseWalkKeys(mc);
        }
        prevSlot = -1;
        placedThisCycle = 0;
        stopTower();
        HeldSlotManager.clear(this);
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null || mc.gameMode == null) return;

        if (!HeldSlotManager.holds(this) && prevSlot >= 0) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            prevSlot = -1;
            setSneak(mc, false);
            releaseWalkKeys(mc);
            return;
        }

        if (mc.gui.screen() != null || !hasPlaceableBlock(p)) { idle(mc, p); return; }

        boolean legit = "Legit".equals(choice("mode"));
        boolean fast = "Fast".equals(choice("mode"));
        boolean realRot = legit && !bool("silentRotation");

        boolean towering = bool("tower") && mc.options != null && mc.options.keyJump.isDown()
            && (bool("whileMoving") || !movingHoriz(p));

        if (towering) {
            HeldSlotManager.request(this, HeldSlotManager.PRIORITY_SCAFFOLD);
            if (!HeldSlotManager.holds(this)) { releaseWalkKeys(mc); return; }
            handleTower(mc, p, level, realRot);
            return;
        }
        if (towerActive) stopTower();

        Direction moveDir = movingHoriz(p) ? horizontalDir(p) : p.getDirection();

        applySafewalk(mc, p, level);
        applyEagle(mc, p, level);
        applySlow(mc, p);
        applyStabilize(mc, p);
        applyZitter(mc, p);

        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_SCAFFOLD);
        if (!HeldSlotManager.holds(this)) return;

        long now = System.currentTimeMillis();

        if (lastTarget != null) {
            if (confirmedFull(level, lastTarget)) { lastTarget = null; retries = 0; }
            else if (now - lastSentMs >= RETRY_MS) {
                if (retries >= MAX_RETRIES || lastHit == null || !isReplaceable(level.getBlockState(lastTarget))) {
                    lastTarget = null; retries = 0;
                } else {
                    if (!ensureBlock(mc, p)) return;
                    placeAt(mc, p, lastHit, realRot);
                    lastSentMs = now; retries++;
                    return;
                }
            }
        }

        if (now - lastPlaceMs < placeDelay(legit, fast, false)) return;
        if (!ensureBlock(mc, p)) return;

        int placed = 0;
        for (BlockPos cell : gatherCells(p, level, false, moveDir, fast)) {
            if (placed >= MAX_PLACE_PER_TICK) break;
            if (!isReplaceable(level.getBlockState(cell))) continue;
            Hit h = hitFor(mc, level, p, cell, moveDir);
            if (h == null) continue;
            placeAt(mc, p, h, realRot);
            lastTarget = cell; lastHit = h; lastSentMs = now; retries = 0;
            placed++;
        }
        if (placed > 0) {
            lastPlaceMs = now;
            placedThisCycle += placed;
        } else if (bool("simulatePlacements") && movingHoriz(p)) {
            // No valid placement this tick — fake a swing so it looks like a legit misclick.
            p.swing(InteractionHand.MAIN_HAND);
        }
    }

    private void placeAt(Minecraft mc, LocalPlayer p, Hit h, boolean realRot) {
        BlockHitResult bhr = new BlockHitResult(h.vec(), h.face(), h.neighbor(), false);
        if (realRot) {
            AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(p);
            AutismRotationUtil.Rotation eased = AutismRotationUtil.interpolate(
                cur, AutismRotationUtil.lookingAt(h.vec(), p.getEyePosition()), 0.35f);
            AutismRotationUtil.apply(p, AutismRotationUtil.normalizeToSensitivity(eased, cur), false);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, bhr);
            p.swing(InteractionHand.MAIN_HAND);
        } else {
            PvpUtil.ghostSafeUseOn(mc, p, bhr);
        }
    }

    private List<BlockPos> gatherCells(LocalPlayer p, Level level, boolean towering, Direction moveDir, boolean fast) {
        List<BlockPos> cells = new ArrayList<>();
        if (towering) { cells.add(p.blockPosition().below()); return cells; }

        double eff = effectiveLookahead(p);
        Vec3 vel = p.getDeltaMovement();
        int ty = targetY(p, eff);
        Vec3 predicted = p.position().add(vel.x * eff, 0.0, vel.z * eff);
        BlockPos primary = new BlockPos(Mth.floor(predicted.x), ty, Mth.floor(predicted.z));

        cells.add(new BlockPos(Mth.floor(p.getX()), targetY(p, 0.0), Mth.floor(p.getZ())));
        cells.add(primary);

        if (movingHoriz(p)) {
            if (fast || horizSpeed(p) > 0.17) cells.add(primary.relative(moveDir));
            if (Math.abs(vel.x) > 0.04 && Math.abs(vel.z) > 0.04) {
                cells.add(primary.relative(vel.x > 0 ? Direction.EAST : Direction.WEST));
                cells.add(primary.relative(vel.z > 0 ? Direction.SOUTH : Direction.NORTH));
            }
            if ("3".equals(choice("width"))) {
                Direction perp = moveDir.getClockWise();
                cells.add(primary.relative(perp));
                cells.add(primary.relative(perp.getOpposite()));
            }
        }
        return dedupe(cells);
    }

    private List<BlockPos> dedupe(List<BlockPos> in) {
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos b : in) if (!out.contains(b)) out.add(b);
        return out;
    }

    private int targetY(LocalPlayer p, double eff) {
        if (bool("down")) return Mth.floor(p.getY()) - 2;
        if (bool("keepY")) return Mth.floor(p.getY()) - 1;
        return Mth.floor(p.getY() + p.getDeltaMovement().y * eff) - 1;
    }

    private double effectiveLookahead(LocalPlayer p) {
        double base = decimal("lookahead");
        if (!bool("speedScaling")) return base;
        return Math.min(MAX_LOOKAHEAD, base + horizSpeed(p) * SPEED_FACTOR);
    }

    private double horizSpeed(LocalPlayer p) {
        Vec3 v = p.getDeltaMovement();
        return Math.sqrt(v.x * v.x + v.z * v.z);
    }

    private Hit hitFor(Minecraft mc, Level level, LocalPlayer p, BlockPos cell, Direction moveDir) {
        Direction behind = moveDir.getOpposite();
        List<Direction> order = new ArrayList<>();
        order.add(Direction.DOWN);
        order.add(behind);
        for (Direction d : Direction.Plane.HORIZONTAL) if (d != behind) order.add(d);
        order.add(Direction.UP);

        Vec3 eyes = p.getEyePosition();
        double reachSq = PLACE_REACH * PLACE_REACH;
        for (Direction dir : order) {
            BlockPos n = cell.relative(dir);
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced() || !ns.getFluidState().isEmpty()) continue;
            if (!ns.isCollisionShapeFullBlock(level, n)) continue;
            Direction face = dir.getOpposite();
            Vec3 vec = pointOnFace(Vec3.atCenterOf(n), face);
            if (vec.distanceToSqr(eyes) > reachSq) continue;
            if (!PvpUtil.canSee(mc, p, eyes, vec)) continue;
            return new Hit(n, face, vec);
        }

        if (bool("airPlace")) {
            Vec3 vec = Vec3.atCenterOf(cell).add(0.0, -0.5, 0.0);
            if (vec.distanceToSqr(eyes) <= reachSq) return new Hit(cell.below(), Direction.UP, vec);
        }
        return null;
    }

    // Pick the click point on the target face. Advanced rotation uses per-axis XYZ ranges; otherwise a small
    // fixed jitter (unchanged legacy behaviour).
    private Vec3 pointOnFace(Vec3 center, Direction face) {
        Vec3 base = center.add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        double jx, jy, jz;
        if (bool("advancedRotation")) {
            jx = rand(decimal("rangeX"));
            jy = rand(decimal("rangeY"));
            jz = rand(decimal("rangeZ"));
        } else {
            jx = jy = jz = 0.0;
            double a = rand(FACE_JITTER), b = rand(FACE_JITTER);
            if (face.getStepX() != 0) { jy = a; jz = b; }
            else if (face.getStepY() != 0) { jx = a; jz = b; }
            else { jx = a; jy = b; }
            return base.add(jx, jy, jz);
        }
        // Advanced: jitter only along the two axes tangent to the face so the point stays on the face.
        if (face.getStepX() != 0) return base.add(0.0, jy, jz);
        if (face.getStepY() != 0) return base.add(jx, 0.0, jz);
        return base.add(jx, jy, 0.0);
    }

    private double rand(double range) {
        return (rng.nextDouble() * 2.0 - 1.0) * range;
    }

    // ---- Tower (Meteor fast tower) ----------------------------------------------------------------------

    private void handleTower(Minecraft mc, LocalPlayer p, Level level, boolean realRot) {
        if (!towerActive) {
            towerActive = true;
            towerCentered = !bool("centerFirst");
        }
        if (!towerCentered) {
            if (centerByWalk(mc, p)) towerCentered = true;
            else return;
        }

        // Meteor-style fast tower: rise at towerSpeed each tick unless a block sits directly above; when it
        // does, snap up to the whole block and mark grounded so we stand on it.
        Vec3 v = p.getDeltaMovement();
        AABB above = p.getBoundingBox().move(0.0, 1.0, 0.0);
        boolean blockAbove = level.getBlockCollisions(p, above).iterator().hasNext();
        if (!blockAbove) {
            p.setDeltaMovement(v.x, decimal("towerSpeed"), v.z);
        } else {
            p.setDeltaMovement(v.x, Math.ceil(p.getY()) - p.getY(), v.z);
            p.setOnGround(true);
        }

        // Place the block directly below the player.
        long now = System.currentTimeMillis();
        if (now - lastPlaceMs >= placeDelay(false, false, true)) {
            BlockPos cell = new BlockPos(Mth.floor(p.getX()), Mth.floor(p.getY()) - 1, Mth.floor(p.getZ()));
            if (isReplaceable(level.getBlockState(cell)) && ensureBlock(mc, p)) {
                Hit h = hitFor(mc, level, p, cell, p.getDirection());
                if (h != null) {
                    placeAt(mc, p, h, realRot);
                    lastTarget = cell; lastHit = h; lastSentMs = now; retries = 0;
                    lastPlaceMs = now;
                }
            }
        }
    }

    public boolean towering() {
        Minecraft mc = Minecraft.getInstance();
        if (!(isEnabled() && bool("tower") && mc != null && mc.player != null
                && mc.options != null && mc.options.keyJump.isDown())) return false;
        return bool("whileMoving") || !movingHoriz(mc.player);
    }

    public float towerLaunch() {
        return (float) decimal("towerSpeed");
    }

    private void stopTower() {
        towerActive = false;
        towerCentered = false;
        towerFloorY = Integer.MIN_VALUE;
        jumpOffY = Double.NaN;
        releaseWalkKeys(Minecraft.getInstance());
    }

    private boolean centerByWalk(Minecraft mc, LocalPlayer p) {
        double cx = Math.floor(p.getX()) + 0.5;
        double cz = Math.floor(p.getZ()) + 0.5;
        double dx = cx - p.getX();
        double dz = cz - p.getZ();
        if (dx * dx + dz * dz <= CENTER_EPS_SQ) {
            releaseWalkKeys(mc);
            return p.getDeltaMovement().horizontalDistanceSqr() <= VEL_EPS_SQ;
        }
        if (playerSteering(mc)) { releaseWalkKeys(mc); return false; }

        double yawRad = Math.toRadians(p.getYRot());
        double sin = Math.sin(yawRad), cos = Math.cos(yawRad);
        double strafe  = dx * cos + dz * sin;
        double forward = -dx * sin + dz * cos;

        setKey(mc, K_FWD,   forward >  INPUT_EPS);
        setKey(mc, K_BACK,  forward < -INPUT_EPS);
        setKey(mc, K_LEFT,  strafe  >  INPUT_EPS);
        setKey(mc, K_RIGHT, strafe  < -INPUT_EPS);
        return false;
    }

    // ---- Movement features -----------------------------------------------------------------------------

    // Zitter: side-to-side to widen placement angle. Smooth uses strafe key input; Teleport nudges velocity.
    private void applyZitter(Minecraft mc, LocalPlayer p) {
        String z = choice("zitter");
        if ("Off".equals(z) || !movingHoriz(p) || !p.onGround() || sneaking) return;
        long now = System.currentTimeMillis();
        if (now - zitterFlipMs > 150L) { zitterSide = !zitterSide; zitterFlipMs = now; }
        Direction perp = horizontalDir(p).getClockWise();
        double sign = zitterSide ? 1.0 : -1.0;
        if ("Smooth".equals(z)) {
            setKey(mc, K_LEFT, zitterSide);
            setKey(mc, K_RIGHT, !zitterSide);
        } else { // Teleport
            Vec3 v = p.getDeltaMovement();
            p.setDeltaMovement(v.x + perp.getStepX() * 0.2 * sign, v.y, v.z + perp.getStepZ() * 0.2 * sign);
        }
    }

    // Eagle: auto-sneak while close to an edge, until the first block of the cycle is placed.
    private void applyEagle(Minecraft mc, LocalPlayer p, Level level) {
        if (!bool("eagle")) return;
        if (!p.onGround()) return;
        if (placedThisCycle > 0) { placedThisCycle = 0; return; }
        Direction move = movingHoriz(p) ? horizontalDir(p) : p.getDirection();
        BlockPos ahead = p.blockPosition().below().relative(move);
        if (!confirmedFull(level, ahead)) setSneak(mc, true);
    }

    // Slow (speed limiter): stop accelerating once horizontal speed exceeds the limit.
    private void applySlow(Minecraft mc, LocalPlayer p) {
        if (!bool("slow")) return;
        if (horizSpeed(p) > decimal("speedLimit")) releaseWalkKeys(mc);
    }

    // StabilizeMovement: remove sideways drift so the bridge stays straight. Skipped while Zitter is on
    // (Zitter deliberately adds sideways motion).
    private void applyStabilize(Minecraft mc, LocalPlayer p) {
        if (!bool("stabilize") || !"Off".equals(choice("zitter")) || !movingHoriz(p)) return;
        Vec3 v = p.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        if (speed < 1e-4) return;
        Direction d = horizontalDir(p);
        // Re-project velocity onto the cardinal movement direction, dropping the perpendicular component.
        double aligned = v.x * d.getStepX() + v.z * d.getStepZ();
        if (aligned <= 0) return;
        p.setDeltaMovement(d.getStepX() * aligned, v.y, d.getStepZ() * aligned);
    }

    // ---- Blocks / slots --------------------------------------------------------------------------------

    private boolean isReplaceable(BlockState s) {
        return s.isAir() || s.canBeReplaced();
    }

    private Direction horizontalDir(LocalPlayer p) {
        Vec3 v = p.getDeltaMovement();
        return Direction.fromYRot((float) Math.toDegrees(Math.atan2(-v.x, v.z)));
    }

    private boolean movingHoriz(LocalPlayer p) {
        Vec3 v = p.getDeltaMovement();
        return v.x * v.x + v.z * v.z > 0.0025;
    }

    private boolean hasPlaceableBlock(LocalPlayer p) {
        return isPlaceable(p.getMainHandItem()) || pickBlockSlot(p) >= 0;
    }

    private boolean ensureBlock(Minecraft mc, LocalPlayer p) {
        if (isPlaceable(p.getMainHandItem())) return true;
        int slot = pickBlockSlot(p);
        if (slot < 0) return false;
        if (prevSlot < 0) prevSlot = p.getInventory().getSelectedSlot();
        AutismInventoryHelper.selectHotbarSlot(mc, slot);
        return false;
    }

    private int pickBlockSlot(LocalPlayer p) {
        if (bool("preferObsidian")) {
            for (int i = 0; i <= 8; i++) if (p.getInventory().getItem(i).is(Items.OBSIDIAN)) return i;
        }
        for (int i = 0; i <= 8; i++) if (isPlaceable(p.getInventory().getItem(i))) return i;
        return -1;
    }

    private boolean isPlaceable(ItemStack s) {
        if (s == null || s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) return false;
        // Never place obviously-bad blocks (LiquidBounce's disallowed set, trimmed).
        if (s.is(Items.TNT) || s.is(Items.COBWEB)) return false;
        if (bool("allowNonFull")) return true;
        return bi.getBlock().defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
    }

    private void restoreSlot(Minecraft mc, LocalPlayer p) {
        if (prevSlot >= 0 && bool("switchBack")) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        prevSlot = -1;
    }

    // ---- SafeWalk (None / Safe / OnEdge) ---------------------------------------------------------------

    private void applySafewalk(Minecraft mc, LocalPlayer p, Level level) {
        String sw = choice("safewalk");
        if ("None".equals(sw)) { if (!bool("eagle")) setSneak(mc, false); return; }

        Direction move = movingHoriz(p) ? horizontalDir(p) : p.getDirection();
        BlockPos below = p.blockPosition().below();
        boolean overGap = !confirmedFull(level, below);
        boolean edgeAhead = movingHoriz(p) && !confirmedFull(level, below.relative(move));
        boolean protect = overGap || edgeAhead;

        if ("Safe".equals(sw)) { setSneak(mc, protect); return; }

        // OnEdge: clamp the velocity component that would carry the player off the block.
        if (!bool("eagle")) setSneak(mc, false);
        if (!protect) return;
        Vec3 d = p.getDeltaMovement();
        double nx = d.x, nz = d.z;
        if (d.x > 0 && !confirmedFull(level, below.east())) nx = 0;
        else if (d.x < 0 && !confirmedFull(level, below.west())) nx = 0;
        if (d.z > 0 && !confirmedFull(level, below.south())) nz = 0;
        else if (d.z < 0 && !confirmedFull(level, below.north())) nz = 0;
        if (nx != d.x || nz != d.z) p.setDeltaMovement(nx, d.y, nz);
    }

    private boolean confirmedFull(Level level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return !s.isAir() && !s.canBeReplaced() && s.isCollisionShapeFullBlock(level, pos);
    }

    // ---- input plumbing --------------------------------------------------------------------------------

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

    private void setSneak(Minecraft mc, boolean down) {
        if (sneaking == down) return;
        if (mc != null && mc.options != null) mc.options.keyShift.setDown(down);
        sneaking = down;
    }

    private void idle(Minecraft mc, LocalPlayer p) {
        HeldSlotManager.release(this);
        restoreSlot(mc, p);
        setSneak(mc, false);
        releaseWalkKeys(mc);
        placedThisCycle = 0;
    }

    private long placeDelay(boolean legit, boolean fast, boolean towering) {
        if (towering) return PvpUtil.jitterMs(90);
        if (fast) return 0L;
        if (legit) return PvpUtil.jitterMs(110, 0.30);
        return PvpUtil.jitterMs(50);
    }
}
