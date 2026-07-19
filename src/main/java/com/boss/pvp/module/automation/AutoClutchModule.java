package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.MenuMode;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismInventoryHelper;
import autismclient.util.AutismRotationUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class AutoClutchModule extends Module {

    private static final double PLACE_REACH = 4.5;

    private long lastPlaceMs = 0L;
    private int prevSlot = -1;

    private record Hit(BlockPos neighbor, Direction face, Vec3 vec) {}

    public AutoClutchModule() {
        super(BossPvpAddon.ID + ":autoclutch", "AutoClutch", "Places a block under you when falling.");

        add(new ChoiceSetting("block", "Block to place", "Any full cube", "Any full cube", "Obsidian", "Cobblestone").group("General"));
        add(new IntSetting("triggerDrop", "Catch when drop deeper than", 2, 1, 16, 1)
            .description("Only places a block if there's no solid ground within this many blocks below you.").group("General"));
        add(new BoolSetting("airPlace", "Air place (catch over void)", true).group("General"));
        add(new IntSetting("delay", "Delay (ms)", 40, 0, 500, 10).group("General").visibleWhen(MenuMode::advanced));
        add(new BoolSetting("silent", "Silent rotation (camera doesn't move)", true).group("General").visibleWhen(MenuMode::advanced));
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && prevSlot >= 0) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        prevSlot = -1;
        HeldSlotManager.clear(this);
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null || mc.gameMode == null || mc.gui.screen() != null) return;

        if (!HeldSlotManager.holds(this) && prevSlot >= 0) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            prevSlot = -1;
            return;
        }

        if (p.onGround() || p.getDeltaMovement().y > 0.0 || !needsCatch(p, level) || !hasBlock(p)) {
            HeldSlotManager.release(this);
            restoreSlot(mc, p);
            return;
        }

        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_AUTOCLUTCH);
        if (!HeldSlotManager.holds(this)) return;

        long now = System.currentTimeMillis();
        if (now - lastPlaceMs < PvpUtil.jitterMs(integer("delay"))) return;
        if (!ensureBlock(mc, p)) return;

        BlockPos feetCell = new BlockPos(Mth.floor(p.getX()), Mth.floor(p.getY()) - 1, Mth.floor(p.getZ()));
        Hit h = hitFor(level, p, feetCell);
        if (h == null) { HeldSlotManager.release(this); restoreSlot(mc, p); return; }
        placeAt(mc, p, h);
        lastPlaceMs = now;
    }

    private boolean needsCatch(LocalPlayer p, Level level) {
        BlockPos feet = new BlockPos(Mth.floor(p.getX()), Mth.floor(p.getY()) - 1, Mth.floor(p.getZ()));
        if (!isReplaceable(level.getBlockState(feet))) return false;
        int drop = integer("triggerDrop");
        for (int i = 1; i <= drop; i++) {
            if (!isReplaceable(level.getBlockState(feet.below(i)))) return false;
        }
        return true;
    }

    private Hit hitFor(Level level, LocalPlayer p, BlockPos cell) {
        Vec3 eyes = p.getEyePosition();
        double reachSq = PLACE_REACH * PLACE_REACH;

        for (Direction dir : Direction.values()) {
            BlockPos n = cell.relative(dir);
            BlockState ns = level.getBlockState(n);
            if (ns.isAir() || ns.canBeReplaced() || !ns.getFluidState().isEmpty()) continue;
            if (!ns.isCollisionShapeFullBlock(level, n)) continue;
            Direction face = dir.getOpposite();
            Vec3 vec = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            if (vec.distanceToSqr(eyes) <= reachSq) return new Hit(n, face, vec);
        }

        if (bool("airPlace")) {
            Vec3 vec = Vec3.atCenterOf(cell).add(0.0, -0.5, 0.0);
            if (vec.distanceToSqr(eyes) <= reachSq) return new Hit(cell.below(), Direction.UP, vec);
        }
        return null;
    }

    private void placeAt(Minecraft mc, LocalPlayer p, Hit h) {
        AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(p);
        AutismRotationUtil.Rotation look = AutismRotationUtil.normalizeToSensitivity(
            AutismRotationUtil.lookingAt(h.vec(), p.getEyePosition()), cur);

        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                look.yaw(), look.pitch(), p.onGround(), p.horizontalCollision));
        }
        if (!bool("silent")) AutismRotationUtil.apply(p, look, false);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
            new BlockHitResult(h.vec(), h.face(), h.neighbor(), false));
        p.swing(InteractionHand.MAIN_HAND);
    }

    private boolean hasBlock(LocalPlayer p) {
        return isPreferred(p.getMainHandItem()) || pickSlot(p) >= 0;
    }

    private boolean ensureBlock(Minecraft mc, LocalPlayer p) {
        if (isPreferred(p.getMainHandItem())) return true;
        int slot = pickSlot(p);
        if (slot < 0) return false;
        if (prevSlot < 0) prevSlot = p.getInventory().getSelectedSlot();
        AutismInventoryHelper.selectHotbarSlot(mc, slot);
        return false;
    }

    private int pickSlot(LocalPlayer p) {
        for (int i = 0; i <= 8; i++) if (isPreferred(p.getInventory().getItem(i))) return i;
        return -1;
    }

    private boolean isPreferred(ItemStack s) {
        if (s == null || s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) return false;
        return switch (choice("block")) {
            case "Obsidian" -> s.is(Items.OBSIDIAN);
            case "Cobblestone" -> s.is(Items.COBBLESTONE);
            default -> bi.getBlock().defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        };
    }

    private boolean isReplaceable(BlockState s) {
        return s.isAir() || s.canBeReplaced();
    }

    private void restoreSlot(Minecraft mc, LocalPlayer p) {
        if (prevSlot >= 0) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        prevSlot = -1;
    }
}
