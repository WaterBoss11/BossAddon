/* Surround/phase candidate planning informed by getMiningBlocks/getPhaseBlocks in the
 * supplied Shoreline AutoMineModule.java. That file contains no copyright/license header;
 * reuse of the supplied bundle was explicitly authorized. No donor mining engine is used.
 */
package com.boss.pvp.module.combat;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.client.mixin.BossCityMineGameModeAccess;
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
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.LinkedHashSet;
import java.util.Set;

public final class CityMineModule extends Module implements HeldSlotManager.Revocable {
    private static final int SLOT_PRIORITY = SpecializedCombatDecisions.PREEMPTIBLE_SLOT_PRIORITY;
    private static final int ROTATION_PRIORITY = 40;
    private LocalPlayer contextPlayer;
    private ClientLevel contextLevel;
    private Player target;
    private BlockPos miningPos;
    private Block miningBlock;
    private Direction miningFace;
    private boolean started;
    private boolean waitingServer;
    private boolean serverDestroyed;
    private boolean aimSubmitted;
    private int actionAge;
    private int waitAge;
    private int cooldown;
    private int previousSlot = -1;
    private int selectedByUs = -1;
    private net.minecraft.world.item.Item selectedItem;
    private RotationUtil.Rotation beforeAim;
    private RotationUtil.Rotation lastAim;
    private String status = "Idle";

    private record Candidate(BlockPos pos, BlockHitResult hit, int toolSlot, double score) {}

    public CityMineModule() {
        super(BossPvpAddon.ID + ":citymine", "CityMine",
            "Plans an opponent's exposed surround/phase block and mines it with vanilla progressive mining. No packet-mine engine.");
        add(new DoubleSetting("targetRange", "Target range", 5.0, 1.0, 6.0, 0.1));
        add(new DoubleSetting("range", "Mining reach cap", 4.5, 1.0, 6.0, 0.1)
            .description("Capped by native block interaction reach; only a visible, actually reachable face is mined."));
        add(new BoolSetting("phaseFirst", "Prioritize blocks intersecting the target", true));
        add(new BoolSetting("headBlocks", "Include upper surround", false));
        add(new BoolSetting("resistantOnly", "Only standard resistant surround blocks", true)
            .description("Obsidian, crying obsidian, ender chests, anchors, netherite blocks and anvils. Other containers are always protected."));
        add(new BoolSetting("autoTool", "Select hotbar tool", true));
        add(new BoolSetting("correctTool", "Require a harvest-capable tool", true));
        add(new BoolSetting("rotate", "Aim at mining face", true));
        add(new IntSetting("maxTicks", "Maximum native mining time", 200, 20, 600, 10));
        add(new IntSetting("delay", "Between-block / abort delay", 10, 5, 60, 1));
    }

    @Override public void onLeaseRevoked() { cancel(Minecraft.getInstance(), true, "Yielded to higher priority item use"); }

    @Override public String info() { return status; }
    @Override public void onDisable() { reset(Minecraft.getInstance(), true); }
    @Override public void onGameLeft() { reset(Minecraft.getInstance(), true); }
    @Override public void onGameJoin() { reset(Minecraft.getInstance(), true); }

    public void tick(Minecraft mc) {
        if (!ready(mc)) { reset(mc, true); return; }
        if (contextPlayer != mc.player || contextLevel != mc.level) {
            reset(mc, true);
            contextPlayer = mc.player;
            contextLevel = mc.level;
        }
        if (!(mc.gameMode instanceof BossCityMineGameModeAccess)) { status = "CityMine native accessor missing"; return; }
        if (cooldown > 0) { cooldown--; return; }
        if (mc.gameMode.isDestroying() && !ownsNativeMining(mc)) { cancel(mc, false, "Manual / other native mining"); return; }
        if (miningPos == null) {
            target = findTarget(mc);
            if (target == null) { status = "No valid player target"; return; }
            Candidate candidate = plan(mc, target);
            if (candidate == null) { status = "No safe reachable surround block / tool"; return; }
            if (!HeldSlotManager.tryAcquire(this, SLOT_PRIORITY)) return;
            miningPos = candidate.pos().immutable();
            miningBlock = mc.level.getBlockState(miningPos).getBlock();
            miningFace = candidate.hit().getDirection();
            previousSlot = mc.player.getInventory().getSelectedSlot();
            selectedByUs = candidate.toolSlot();
            selectedItem = mc.player.getInventory().getItem(selectedByUs).getItem();
            InventoryUtil.selectHotbarSlot(mc, selectedByUs);
            if (mc.player.getInventory().getSelectedSlot() != selectedByUs) { cancel(mc, false, "Tool selection refused"); return; }
            actionAge = waitAge = 0;
            started = waitingServer = serverDestroyed = aimSubmitted = false;
        }
        if (!HeldSlotManager.holds(this) || !HeldSlotManager.tryAcquire(this, SLOT_PRIORITY)
            || mc.player.getInventory().getSelectedSlot() != selectedByUs
            || mc.player.getMainHandItem().getItem() != selectedItem) { cancel(mc, true, "Item ownership lost"); return; }
        if (!validTarget(mc, target) || !candidateCells(target).contains(miningPos) || protectedBlock(mc, miningPos)) {
            cancel(mc, true, "Target / surround changed or protected");
            return;
        }
        if (++actionAge > integer("maxTicks")) { cancel(mc, true, "Native mining timeout"); return; }
        if (serverDestroyed) { cancel(mc, true, "Server confirmed block removal"); return; }
        BlockState state = mc.level.getBlockState(miningPos);
        if (waitingServer || state.isAir()) {
            waitingServer = true;
            status = "Awaiting native server result";
            if (++waitAge > 20) cancel(mc, true, "Native result unconfirmed; backing off");
            return;
        }
        if (!state.is(miningBlock) || !mineable(mc, miningPos, state)
            || !usableTool(mc.player.getMainHandItem(), state)) { cancel(mc, true, "Block / tool no longer valid"); return; }
        BlockHitResult hit = visibleFace(mc, miningPos);
        if (hit == null || !aim(mc, hit)) { cancelIfStarted(mc, "Face / rotation unavailable"); return; }
        miningFace = hit.getDirection();
        if (!started) {
            boolean accepted = mc.gameMode.startDestroyBlock(miningPos, miningFace);
            started = accepted && (ownsNativePosition(mc) || mc.level.getBlockState(miningPos).isAir());
            if (!started) { cancel(mc, false, "Native mining start refused"); return; }
        } else {
            if (!ownsNativeMining(mc)) { cancel(mc, false, "Native mining was interrupted"); return; }
            mc.gameMode.continueDestroyBlock(miningPos, miningFace);
        }
        if (mc.level.getBlockState(miningPos).isAir()) waitingServer = true;
        mc.player.swing(InteractionHand.MAIN_HAND);
        status = "Native mining " + miningPos.getX() + "," + miningPos.getY() + "," + miningPos.getZ();
    }

    private boolean ready(Minecraft mc) {
        return isEnabled() && mc != null && mc.player != null && mc.level != null && mc.gameMode != null
            && mc.getConnection() != null && mc.player.isAlive() && !mc.player.isSpectator() && !mc.isPaused()
            && mc.gameMode.getPlayerMode().isSurvival() && mc.player.mayBuild() && mc.gui.screen() == null
            && mc.player.containerMenu == mc.player.inventoryMenu && mc.player.inventoryMenu.getCarried().isEmpty()
            && !mc.options.keyAttack.isDown() && !mc.options.keyUse.isDown() && !mc.player.isUsingItem()
            && !CombatManager.isCombatPaused() && !PvpUtil.hostEnabled("hide")
            && !PvpUtil.hostEnabled("packet-mine") && !PvpUtil.hostEnabled("instant-rebreak");
    }

    private Player findTarget(Minecraft mc) {
        var shared = CombatManager.currentTarget(mc);
        if (shared instanceof Player player && validTarget(mc, player)) return player;
        var found = PvpUtil.findTarget(mc, mc.player, decimal("targetRange"), 180,
            Set.of("minecraft:player"), false, "Distance");
        return found instanceof Player player && validTarget(mc, player) ? player : null;
    }

    private boolean validTarget(Minecraft mc, Player player) {
        return player != null && player != mc.player && player.level() == mc.level && player.isAlive()
            && !player.isRemoved() && !player.isSpectator() && player.isAttackable() && !PvpUtil.isProtectedPlayer(mc, player)
            && player.distanceToSqr(mc.player) <= decimal("targetRange") * decimal("targetRange");
    }

    private Set<BlockPos> candidateCells(Player player) {
        AABB box = player.getBoundingBox();
        int minX = Mth.floor(box.minX + 1.0e-5), maxX = Mth.floor(box.maxX - 1.0e-5);
        int minZ = Mth.floor(box.minZ + 1.0e-5), maxZ = Mth.floor(box.maxZ - 1.0e-5);
        int y = Mth.floor(box.minY + 1.0e-5);
        Set<BlockPos> cells = new LinkedHashSet<>();
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos feet = new BlockPos(x, y, z);
                cells.add(feet);
                if (bool("headBlocks")) cells.add(feet.above());
                for (Direction side : Direction.Plane.HORIZONTAL) {
                    BlockPos surround = feet.relative(side);
                    cells.add(surround);
                    if (bool("headBlocks")) cells.add(surround.above());
                }
            }
        }
        return cells;
    }

    private Candidate plan(Minecraft mc, Player player) {
        Candidate best = null;
        for (BlockPos pos : candidateCells(player)) {
            BlockState state = mc.level.getBlockState(pos);
            if (!mineable(mc, pos, state)) continue;
            BlockHitResult hit = visibleFace(mc, pos);
            if (hit == null) continue;
            int tool = bestTool(mc.player, state);
            if (tool < 0) continue;
            boolean phase = new AABB(pos).intersects(player.getBoundingBox());
            double speed = Math.max(0.1, mc.player.getInventory().getItem(tool).getDestroySpeed(state));
            double score = (bool("phaseFirst") && !phase ? 10000 : 0)
                + state.getDestroySpeed(mc.level, pos) / speed * 10
                + mc.player.getEyePosition().distanceToSqr(hit.getLocation());
            if (best == null || score < best.score()) best = new Candidate(pos, hit, tool, score);
        }
        return best;
    }

    private boolean mineable(Minecraft mc, BlockPos pos, BlockState state) {
        if (!mc.level.hasChunkAt(pos) || !mc.level.isInWorldBounds(pos) || !mc.level.getWorldBorder().isWithinBounds(pos)
            || state.isAir() || state.canBeReplaced() || !state.getFluidState().isEmpty()
            || state.getDestroySpeed(mc.level, pos) < 0 || protectedBlock(mc, pos)
            || mc.player.blockActionRestricted(mc.level, pos, mc.gameMode.getPlayerMode())
            || state.getCollisionShape(mc.level, pos).isEmpty()) return false;
        if (state.hasBlockEntity() && !state.is(Blocks.ENDER_CHEST)) return false;
        return !bool("resistantOnly") || state.is(Blocks.OBSIDIAN) || state.is(Blocks.CRYING_OBSIDIAN)
            || state.is(Blocks.ENDER_CHEST) || state.is(Blocks.RESPAWN_ANCHOR) || state.is(Blocks.NETHERITE_BLOCK)
            || state.is(Blocks.ANVIL) || state.is(Blocks.CHIPPED_ANVIL) || state.is(Blocks.DAMAGED_ANVIL);
    }

    private boolean protectedBlock(Minecraft mc, BlockPos pos) {
        for (Player player : mc.level.players()) {
            if (player != mc.player && !PvpUtil.isProtectedPlayer(mc, player)) continue;
            if (player.isSpectator()) continue;
            AABB box = player.getBoundingBox();
            if (SpecializedCombatDecisions.protectedCell(pos.getX(), pos.getY(), pos.getZ(),
                box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)) return true;
        }
        return false;
    }

    private int bestTool(LocalPlayer player, BlockState state) {
        int selected = player.getInventory().getSelectedSlot();
        if (!bool("autoTool")) return usableTool(player.getMainHandItem(), state) ? selected : -1;
        int best = -1;
        double bestSpeed = -1;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!usableTool(stack, state)) continue;
            double speed = stack.getDestroySpeed(state) * (stack.isCorrectToolForDrops(state) ? 1.0 : 0.3);
            if (speed > bestSpeed || speed == bestSpeed && slot == selected) { best = slot; bestSpeed = speed; }
        }
        return best;
    }

    private boolean usableTool(ItemStack stack, BlockState state) {
        return (!stack.isDamageableItem() || stack.getMaxDamage() - stack.getDamageValue() > 1)
            && (!bool("correctTool") || !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state));
    }

    private BlockHitResult visibleFace(Minecraft mc, BlockPos pos) {
        BlockState state = mc.level.getBlockState(pos);
        var shape = state.getShape(mc.level, pos, CollisionContext.of(mc.player));
        if (shape.isEmpty()) return null;
        AABB bounds = shape.bounds().move(pos);
        Vec3 center = bounds.getCenter();
        Vec3 eyes = mc.player.getEyePosition();
        double reach = Math.min(decimal("range"), mc.player.blockInteractionRange());
        BlockHitResult best = null;
        double distance = reach * reach;
        for (Direction face : Direction.values()) {
            Vec3 point = new Vec3(face == Direction.WEST ? bounds.minX : face == Direction.EAST ? bounds.maxX : center.x,
                face == Direction.DOWN ? bounds.minY : face == Direction.UP ? bounds.maxY : center.y,
                face == Direction.NORTH ? bounds.minZ : face == Direction.SOUTH ? bounds.maxZ : center.z);
            if (eyes.distanceToSqr(point) > distance) continue;
            BlockHitResult hit = mc.level.clip(new ClipContext(eyes,
                point.add(-face.getStepX() * 0.01, -face.getStepY() * 0.01, -face.getStepZ() * 0.01),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos) || hit.getDirection() != face) continue;
            if (!bool("rotate") && (!(mc.hitResult instanceof BlockHitResult current) || !current.getBlockPos().equals(pos))) continue;
            best = hit;
            distance = eyes.distanceToSqr(hit.getLocation());
        }
        return best;
    }

    private boolean aim(Minecraft mc, BlockHitResult hit) {
        if (!bool("rotate")) return true;
        RotationUtil.Rotation look = RotationUtil.normalizeToSensitivity(RotationUtil.lookingAt(hit.getLocation(), mc.player.getEyePosition()),
            RotationUtil.playerRotation(mc.player));
        RotationManager.Request committed = RotationManager.committed();
        if (committed != null && committed.priority() > ROTATION_PRIORITY) { aimSubmitted = false; return false; }
        RotationManager.submit(look.yaw(), look.pitch(), ROTATION_PRIORITY, false);
        boolean aligned = aimSubmitted && committed != null && committed.priority() == ROTATION_PRIORITY
            && Math.abs(Mth.wrapDegrees(committed.yaw() - look.yaw())) < 2 && Math.abs(committed.pitch() - look.pitch()) < 2;
        aimSubmitted = true;
        if (!aligned) return false;
        RotationUtil.Rotation current = RotationUtil.playerRotation(mc.player);
        if (lastAim == null || !sameRotation(current, lastAim)) beforeAim = current;
        RotationUtil.apply(mc.player, look, false);
        lastAim = RotationUtil.playerRotation(mc.player);
        return true;
    }

    private boolean ownsNativePosition(Minecraft mc) {
        return miningPos != null && mc != null && mc.player == contextPlayer && mc.level == contextLevel
            && mc.gameMode instanceof BossCityMineGameModeAccess access && miningPos.equals(access.bossCityMine$destroyPos());
    }

    private boolean ownsNativeMining(Minecraft mc) {
        return ownsNativePosition(mc) && mc.gameMode.isDestroying();
    }

    /** Suppresses only Minecraft.continueAttack's idle stop, never another miner's explicit stop. */
    public boolean keepNativeMining(Minecraft mc) {
        return ready(mc) && started && !waitingServer && ownsNativeMining(mc) && HeldSlotManager.holds(this)
            && mc.player.getInventory().getSelectedSlot() == selectedByUs && validTarget(mc, target)
            && mc.player.getMainHandItem().getItem() == selectedItem && !protectedBlock(mc, miningPos);
    }

    /** Called before vanilla mouse-attack handling, without modifying any key or hit result. */
    public void yieldToManualInput(Minecraft mc) {
        if (miningPos != null) cancel(mc, true, "Yielded to manual attack");
    }

    public void onServerBlockUpdate(BlockPos pos, BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        if (!isEnabled() || !started || miningPos == null || !miningPos.equals(pos)
            || mc.player != contextPlayer || mc.level != contextLevel) return;
        if (state.isAir() || state.canBeReplaced()) serverDestroyed = true;
        else if (waitingServer) cancel(mc, false, "Server retained the block; backing off");
    }

    private void cancelIfStarted(Minecraft mc, String reason) {
        if (started) cancel(mc, true, reason);
        else status = "Waiting for visible mining rotation";
    }

    private static boolean sameRotation(RotationUtil.Rotation a, RotationUtil.Rotation b) {
        return Math.abs(Mth.wrapDegrees(a.yaw() - b.yaw())) < 0.001f && Math.abs(a.pitch() - b.pitch()) < 0.001f;
    }

    private void cancel(Minecraft mc, boolean stopOwnedMining, String reason) {
        boolean sameContext = mc != null && mc.player == contextPlayer && mc.level == contextLevel && mc.player != null;
        boolean stop = stopOwnedMining && started && ownsNativeMining(mc);
        started = false;
        if (stop) mc.gameMode.stopDestroyBlock();
        if (sameContext) {
            if (previousSlot >= 0 && HeldSlotManager.holds(this) && mc.player.getInventory().getSelectedSlot() == selectedByUs
                && mc.player.getMainHandItem().getItem() == selectedItem) InventoryUtil.selectHotbarSlot(mc, previousSlot);
            if (beforeAim != null && lastAim != null && sameRotation(RotationUtil.playerRotation(mc.player), lastAim)) {
                RotationUtil.apply(mc.player, beforeAim, false);
            }
        }
        HeldSlotManager.clear(this);
        target = null;
        miningPos = null;
        miningBlock = null;
        miningFace = null;
        selectedItem = null;
        previousSlot = selectedByUs = -1;
        waitingServer = serverDestroyed = aimSubmitted = false;
        beforeAim = lastAim = null;
        actionAge = waitAge = 0;
        cooldown = integer("delay");
        status = reason;
    }

    private void reset(Minecraft mc, boolean stopOwnedMining) {
        cancel(mc, stopOwnedMining, "Idle");
        contextPlayer = null;
        contextLevel = null;
        cooldown = 0;
    }
}
