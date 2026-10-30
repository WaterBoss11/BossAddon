/* Layout search adapted from ThunderHack's PistonPush.java, distributed under GPL-3.0
 * in the supplied repository LICENSE. The donor Java file has no individual copyright notice.
 * Native placement, acknowledgement and inventory ownership below are not donor managers/events.
 */
package com.boss.pvp.module.combat;

import autismclient.api.module.DoubleSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.CombatManager;
import com.boss.pvp.util.InventoryUtil;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.RotationManager;
import com.boss.pvp.util.pvp.RotationUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.Set;

import static com.boss.pvp.module.combat.SpecializedCombatDecisions.PushStep;

public final class PistonPushModule extends Module implements HeldSlotManager.Revocable {
    private static final int SLOT_PRIORITY = SpecializedCombatDecisions.PREEMPTIBLE_SLOT_PRIORITY;
    private static final int ROTATION_PRIORITY = 80;
    private final SpecializedCombatDecisions.PushSequence sequence = new SpecializedCombatDecisions.PushSequence();
    private LocalPlayer contextPlayer;
    private ClientLevel contextLevel;
    private Player target;
    private BlockPos targetAnchor;
    private BlockPos pistonPos;
    private BlockPos powerPos;
    private Direction facing;
    private Block pistonBlock;
    private boolean pistonRejected;
    private boolean powerRejected;
    private boolean aimSubmitted;
    private int cooldown;
    private int selectedByUs = -1;
    private int previousSlot = -1;
    private String status = "Idle";

    private record Placement(BlockPos pos, BlockHitResult hit, RotationUtil.Rotation look) {}

    public PistonPushModule() {
        super(BossPvpAddon.ID + ":pistonpush", "PistonPush",
            "Places a facing piston, then a redstone block after server confirmation. Leaves placed blocks in the world.");
        add(new DoubleSetting("range", "Target range", 4.5, 1.0, 6.0, 0.1));
        add(new DoubleSetting("placeRange", "Placement reach cap", 4.5, 1.0, 6.0, 0.1)
            .description("Also capped by the server-provided native block interaction range. Requires visible support faces."));
        add(new IntSetting("waitTicks", "Server confirmation timeout", 12, 4, 40, 1));
        add(new IntSetting("retries", "Confirmed-rejection retries", 1, 0, 2, 1)
            .description("Retries only after an authoritative replaceable-block update, never a prediction or missing acknowledgement."));
        add(new IntSetting("cooldown", "Between-plan cooldown (ticks)", 30, 10, 100, 1));
    }

    @Override public void onLeaseRevoked() { abort(Minecraft.getInstance(), "Yielded to higher priority item use"); }

    @Override public String info() { return status; }
    @Override public void onDisable() { reset(Minecraft.getInstance()); }
    @Override public void onGameLeft() { reset(Minecraft.getInstance()); }
    @Override public void onGameJoin() { reset(Minecraft.getInstance()); }

    public void tick(Minecraft mc) {
        if (!ready(mc)) { reset(mc); return; }
        if (contextPlayer != mc.player || contextLevel != mc.level) {
            reset(mc);
            contextPlayer = mc.player;
            contextLevel = mc.level;
        }
        if (!restoreSlot(mc)) { abort(mc, "Slot restoration deferred"); return; }
        if (cooldown > 0) { cooldown--; return; }
        if (pistonPos == null) {
            target = findTarget(mc);
            if (target == null || !plan(mc)) { status = "No safe reachable layout / hotbar items"; return; }
            sequence.start();
            status = "Orienting piston";
        }
        if (sequence.step() == PushStep.COMPLETE) {
            abort(mc, "Server-confirmed extension");
            return;
        }
        if (!validTarget(mc, target)) { abort(mc, "Target lost / protected"); return; }
        if (!sequence.tick(integer("waitTicks") * 4 + 20)) { abort(mc, "Plan time budget exhausted"); return; }
        PushStep step = sequence.step();
        if ((step == PushStep.PISTON || step == PushStep.POWER) && !target.blockPosition().equals(targetAnchor)) {
            abort(mc, "Target moved before power");
            return;
        }
        switch (step) {
            case PISTON, POWER -> placeStep(mc, step == PushStep.PISTON);
            case PISTON_ACK, POWER_ACK -> {
                if (sequence.age() > integer("waitTicks")) {
                    boolean rejected = step == PushStep.PISTON_ACK ? pistonRejected : powerRejected;
                    if (rejected && sequence.retryRejected(integer("retries"))) {
                        pistonRejected = powerRejected = aimSubmitted = false;
                        status = "Retrying confirmed rejection";
                    } else abort(mc, "Server placement not confirmed");
                }
            }
            case EXTENSION -> {
                if (sequence.age() > integer("waitTicks")) abort(mc, "Power confirmed; extension not confirmed");
            }
            default -> { }
        }
    }

    private boolean ready(Minecraft mc) {
        return isEnabled() && mc != null && mc.player != null && mc.level != null && mc.gameMode != null
            && mc.getConnection() != null && !mc.isPaused() && mc.player.isAlive() && !mc.player.isSpectator()
            && mc.gameMode.getPlayerMode().isSurvival() && mc.player.mayBuild() && mc.gui.screen() == null
            && mc.player.containerMenu == mc.player.inventoryMenu && mc.player.inventoryMenu.getCarried().isEmpty()
            && !mc.player.isUsingItem() && !mc.gameMode.isDestroying() && !mc.options.keyAttack.isDown()
            && !mc.options.keyUse.isDown() && !mc.options.keyShift.isDown()
            && !CombatManager.isCombatPaused() && !PvpUtil.hostEnabled("hide");
    }

    private Player findTarget(Minecraft mc) {
        var shared = CombatManager.currentTarget(mc);
        if (shared instanceof Player player && validTarget(mc, player)) return player;
        var found = PvpUtil.findTarget(mc, mc.player, decimal("range"), 180,
            Set.of("minecraft:player"), false, "Distance");
        return found instanceof Player player && validTarget(mc, player) ? player : null;
    }

    private boolean validTarget(Minecraft mc, Player player) {
        return player != null && player != mc.player && player.level() == mc.level && player.isAlive()
            && !player.isRemoved() && !player.isSpectator() && player.isAttackable() && !PvpUtil.isProtectedPlayer(mc, player)
            && player.distanceToSqr(mc.player) <= decimal("range") * decimal("range");
    }

    private boolean plan(Minecraft mc) {
        int piston = findItem(mc.player, Items.PISTON);
        pistonBlock = Blocks.PISTON;
        if (piston < 0) { piston = findItem(mc.player, Items.STICKY_PISTON); pistonBlock = Blocks.STICKY_PISTON; }
        if (piston < 0 || findItem(mc.player, Items.REDSTONE_BLOCK) < 0) return false;
        targetAnchor = target.blockPosition().immutable();
        double best = Double.MAX_VALUE;
        BlockPos bestPiston = null;
        BlockPos bestPower = null;
        Direction bestFacing = null;
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos base = targetAnchor.relative(side).above();
            Direction push = side.getOpposite();
            if (!freeCell(mc, base) || !safePush(mc, base, push) || mc.level.hasNeighborSignal(base)) continue;
            BlockState expectedPiston = pistonBlock.defaultBlockState().setValue(PistonBaseBlock.FACING, push);
            Placement pistonPlacement = placement(mc, base, expectedPiston, push,
                mc.player.getInventory().getItem(piston));
            if (pistonPlacement == null) continue;
            for (Direction powerSide : Direction.values()) {
                if (powerSide == push) continue;
                BlockPos power = base.relative(powerSide);
                if (!freeCell(mc, power) || new AABB(power).intersects(target.getBoundingBox())
                    || protectedArea(mc, new AABB(power).inflate(0.05))) continue;
                Placement powerPlacement = placement(mc, power, Blocks.REDSTONE_BLOCK.defaultBlockState(), null,
                    mc.player.getInventory().getItem(findItem(mc.player, Items.REDSTONE_BLOCK)));
                if (powerPlacement == null) continue;
                double score = mc.player.getEyePosition().distanceToSqr(pistonPlacement.hit().getLocation())
                    + mc.player.getEyePosition().distanceToSqr(powerPlacement.hit().getLocation());
                if (score < best) { best = score; bestPiston = base; bestPower = power; bestFacing = push; }
            }
        }
        pistonPos = bestPiston;
        powerPos = bestPower;
        facing = bestFacing;
        return pistonPos != null;
    }

    private boolean safePush(Minecraft mc, BlockPos piston, Direction push) {
        BlockPos head = piston.relative(push);
        if (!freeCell(mc, head) || protectedArea(mc, new AABB(piston).inflate(0.05))) return false;
        Vec3 offset = new Vec3(push.getStepX() * 1.05, 0, push.getStepZ() * 1.05);
        AABB destination = target.getBoundingBox().move(offset);
        AABB swept = target.getBoundingBox().expandTowards(offset).inflate(0.05);
        if (!mc.level.getWorldBorder().isWithinBounds(destination) || !mc.level.noCollision(target, destination)
            || protectedArea(mc, swept)) return false;
        PistonStructureResolver resolver = new PistonStructureResolver(mc.level, piston, push, true);
        // ponytail: only empty-head, unobstructed pushes; support/block-moving puzzles require a separate planner.
        return resolver.resolve() && resolver.getToPush().isEmpty() && resolver.getToDestroy().isEmpty();
    }

    private boolean protectedArea(Minecraft mc, AABB area) {
        for (Player player : mc.level.players()) {
            if ((player == mc.player || PvpUtil.isProtectedPlayer(mc, player)) && !player.isSpectator()
                && player.getBoundingBox().inflate(0.05).intersects(area)) return true;
        }
        return false;
    }

    private boolean freeCell(Minecraft mc, BlockPos pos) {
        if (!mc.level.hasChunkAt(pos) || !mc.level.isInWorldBounds(pos) || !mc.level.getWorldBorder().isWithinBounds(pos)) return false;
        BlockState state = mc.level.getBlockState(pos);
        return state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    private Placement placement(Minecraft mc, BlockPos pos, BlockState expected, Direction requiredFacing, ItemStack stack) {
        if (!freeCell(mc, pos) || !expected.canSurvive(mc.level, pos)
            || !mc.level.isUnobstructed(expected, pos, CollisionContext.of(mc.player))) return null;
        Vec3 eyes = mc.player.getEyePosition();
        double reach = Math.min(decimal("placeRange"), mc.player.blockInteractionRange());
        Placement best = null;
        double bestDistance = reach * reach;
        for (Direction face : Direction.values()) {
            BlockPos support = pos.relative(face.getOpposite());
            if (!mc.level.hasChunkAt(support)) continue;
            BlockState state = mc.level.getBlockState(support);
            if (state.canBeReplaced() || state.hasBlockEntity() || state.isSignalSource() || interactiveSupport(state)
                || !state.isCollisionShapeFullBlock(mc.level, support) || !state.isFaceSturdy(mc.level, support, face)) continue;
            Vec3 point = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            double distance = eyes.distanceToSqr(point);
            if (distance > bestDistance) continue;
            BlockHitResult visible = mc.level.clip(new ClipContext(eyes,
                point.add(-face.getStepX() * 0.01, -face.getStepY() * 0.01, -face.getStepZ() * 0.01),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
            if (visible.getType() != HitResult.Type.BLOCK || !visible.getBlockPos().equals(support)
                || visible.getDirection() != face) continue;
            RotationUtil.Rotation look = RotationUtil.normalizeToSensitivity(RotationUtil.lookingAt(point, eyes),
                RotationUtil.playerRotation(mc.player));
            if (requiredFacing != null && (Math.abs(look.pitch()) >= 30
                || Direction.fromYRot(look.yaw()).getOpposite() != requiredFacing)) continue;
            BlockPlaceContext context = new BlockPlaceContext(mc.player, InteractionHand.MAIN_HAND, stack, visible);
            if (!context.canPlace() || !context.getClickedPos().equals(pos)
                || !mc.player.mayUseItemAt(support, face, stack)) continue;
            bestDistance = distance;
            best = new Placement(pos, visible, look);
        }
        return best;
    }

    private static boolean interactiveSupport(BlockState state) {
        return state.is(Blocks.CRAFTING_TABLE) || state.is(Blocks.SMITHING_TABLE) || state.is(Blocks.CARTOGRAPHY_TABLE)
            || state.is(Blocks.LOOM) || state.is(Blocks.STONECUTTER) || state.is(Blocks.GRINDSTONE)
            || state.is(Blocks.ANVIL) || state.is(Blocks.CHIPPED_ANVIL) || state.is(Blocks.DAMAGED_ANVIL)
            || state.is(Blocks.NOTE_BLOCK) || state.is(Blocks.RESPAWN_ANCHOR);
    }

    private void placeStep(Minecraft mc, boolean piston) {
        if (!safePush(mc, pistonPos, facing) || mc.level.hasNeighborSignal(pistonPos)) {
            abort(mc, "Push space / power changed");
            return;
        }
        if (!piston) {
            BlockState state = mc.level.getBlockState(pistonPos);
            if (!state.is(pistonBlock) || state.getValue(PistonBaseBlock.FACING) != facing
                || state.getValue(PistonBaseBlock.EXTENDED)) { abort(mc, "Confirmed piston changed"); return; }
        }
        int slot = findItem(mc.player, piston ? pistonBlock.asItem() : Items.REDSTONE_BLOCK);
        if (slot < 0) { abort(mc, "Required hotbar item missing"); return; }
        BlockPos pos = piston ? pistonPos : powerPos;
        BlockState expected = piston ? pistonBlock.defaultBlockState().setValue(PistonBaseBlock.FACING, facing)
            : Blocks.REDSTONE_BLOCK.defaultBlockState();
        Placement placement = placement(mc, pos, expected, piston ? facing : null, mc.player.getInventory().getItem(slot));
        if (placement == null) { abort(mc, "Placement face no longer valid"); return; }
        if (!HeldSlotManager.tryAcquire(this, SLOT_PRIORITY)) { aimSubmitted = false; return; }
        RotationManager.Request committed = RotationManager.committed();
        if (committed != null && committed.priority() > ROTATION_PRIORITY) { aimSubmitted = false; return; }
        RotationUtil.Rotation look = placement.look();
        RotationManager.submit(look.yaw(), look.pitch(), ROTATION_PRIORITY, false);
        boolean aligned = aimSubmitted && committed != null && committed.priority() == ROTATION_PRIORITY
            && Math.abs(Mth.wrapDegrees(committed.yaw() - look.yaw())) < 2
            && Math.abs(committed.pitch() - look.pitch()) < 2;
        aimSubmitted = true;
        if (!aligned) return;
        LocalPlayer player = mc.player;
        RotationUtil.Rotation before = RotationUtil.playerRotation(player);
        float oldYaw = player.yRotO;
        float oldPitch = player.xRotO;
        previousSlot = player.getInventory().getSelectedSlot();
        selectedByUs = slot;
        try {
            InventoryUtil.selectHotbarSlot(mc, slot);
            if (player.getInventory().getSelectedSlot() != slot || !HeldSlotManager.holds(this)
                || !player.getMainHandItem().is(piston ? pistonBlock.asItem() : Items.REDSTONE_BLOCK)) {
                abort(mc, "Hotbar selection refused");
                return;
            }
            RotationUtil.apply(player, look, false);
            BlockPlaceContext context = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, player.getMainHandItem(), placement.hit());
            if (piston && ((PistonBaseBlock) pistonBlock).getStateForPlacement(context).getValue(PistonBaseBlock.FACING) != facing) {
                abort(mc, "Native piston orientation mismatch");
                return;
            }
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(look.yaw(), look.pitch(), player.onGround(), player.horizontalCollision));
            sequence.sent();
            pistonRejected = powerRejected = false;
            var result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, placement.hit());
            if (!result.consumesAction()) { abort(mc, "Native placement refused"); return; }
            player.swing(InteractionHand.MAIN_HAND);
            aimSubmitted = false;
            status = piston ? "Awaiting authoritative piston" : "Awaiting authoritative power";
        } finally {
            if (mc.player == player && mc.level == contextLevel
                && Math.abs(Mth.wrapDegrees(player.getYRot() - look.yaw())) < 0.001f
                && Math.abs(player.getXRot() - look.pitch()) < 0.001f) {
                player.setYRot(before.yaw());
                player.setXRot(before.pitch());
                player.yRotO = oldYaw;
                player.xRotO = oldPitch;
                if (mc.getConnection() != null) mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                    before.yaw(), before.pitch(), player.onGround(), player.horizontalCollision));
            }
            restoreSlot(mc);
            HeldSlotManager.clear(this);
        }
    }

    /** Authoritative packet states only; never called from local placement prediction. */
    public void onServerBlockUpdate(BlockPos pos, BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || pistonPos == null || mc.player != contextPlayer || mc.level != contextLevel) return;
        if (pos.equals(pistonPos)) {
            if (state.is(pistonBlock) && state.getValue(PistonBaseBlock.FACING) == facing) {
                if ((sequence.step() == PushStep.PISTON_ACK || sequence.step() == PushStep.POWER)
                    && state.getValue(PistonBaseBlock.EXTENDED)) { abort(mc, "Piston powered before our power step"); return; }
                sequence.confirmPiston(true, state.getValue(PistonBaseBlock.EXTENDED));
            } else if (state.canBeReplaced()) pistonRejected = true;
            else abort(mc, "Server piston state differs");
        } else if (pos.equals(powerPos)) {
            if (state.is(Blocks.REDSTONE_BLOCK)) sequence.confirmPower();
            else if (state.canBeReplaced()) powerRejected = true;
            else abort(mc, "Server power state differs");
        }
    }

    private static int findItem(LocalPlayer player, net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < 9; slot++) if (player.getInventory().getItem(slot).is(item)) return slot;
        return -1;
    }

    private boolean restoreSlot(Minecraft mc) {
        if (previousSlot < 0) return true;
        if (mc != null && mc.player == contextPlayer && mc.level == contextLevel && HeldSlotManager.holds(this)
            && mc.player.getInventory().getSelectedSlot() == selectedByUs) {
            InventoryUtil.selectHotbarSlot(mc, previousSlot);
            if (mc.player.getInventory().getSelectedSlot() != previousSlot) return false;
        }
        previousSlot = selectedByUs = -1;
        return true;
    }

    private void abort(Minecraft mc, String reason) {
        restoreSlot(mc);
        HeldSlotManager.clear(this);
        sequence.reset();
        target = null;
        pistonPos = powerPos = targetAnchor = null;
        facing = null;
        pistonBlock = null;
        pistonRejected = powerRejected = aimSubmitted = false;
        cooldown = integer("cooldown");
        status = reason;
    }

    private void reset(Minecraft mc) {
        abort(mc, "Idle");
        contextPlayer = null;
        contextLevel = null;
        cooldown = 0;
        previousSlot = selectedByUs = -1;
    }
}
