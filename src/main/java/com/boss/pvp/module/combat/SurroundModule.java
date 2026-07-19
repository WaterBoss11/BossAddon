package com.boss.pvp.module.combat;

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
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class SurroundModule extends Module {

    private static final double CENTER_EPS_SQ = 0.01;
    private static final double INPUT_EPS = 0.02;
    private static final double VEL_EPS_SQ = 0.0016;
    private static final float LEGIT_EASE = 0.25f;

    private static final int K_FWD = 0, K_BACK = 1, K_LEFT = 2, K_RIGHT = 3;

    private long lastPlaceMs = 0L;
    private int prevSlot = -1;
    private boolean surroundComplete = false;
    private Vec3 legitAim = null;

    private final boolean[] myKeys = new boolean[4];

    public SurroundModule() {
        super(BossPvpAddon.ID + ":surround", "Surround", "Surround your feet with obsidian to block crystal damage.");

        add(new ChoiceSetting("block", "Block", "Obsidian", "Obsidian", "Crying Obsidian", "Cobblestone").group("General"));
        add(new ChoiceSetting("expand", "Coverage", "Sides", "Sides", "Sides + Under", "Two High").group("General"));
        add(new BoolSetting("onlyWhenThreatened", "Only when threatened", true).group("General"));
        add(new BoolSetting("autoDisableOnMove", "Pause while moving", false).group("General"));
        add(new BoolSetting("replaceMined", "Replace mined blocks", true).group("General"));
        add(new IntSetting("blocksPerTick", "Blocks per tick", 1, 1, 4, 1).group("General"));
        add(new IntSetting("delay", "Delay (ms)", 50, 0, 1000, 10).group("General"));
        add(new BoolSetting("centerFirst", "Walk to block center first", true).group("General"));
        add(new BoolSetting("teamCheck", "Ignore teammates", false)
            .description("Don't treat players wearing leather armour dyed your colour as a threat (teammates).").group("Team"));
        add(new ChoiceSetting("rotationMode", "Rotation", "Silent", "Silent", "Real").group("General"));
        add(new DoubleSetting("legitEase", "Camera turn speed", 0.25, 0.05, 1.0, 0.05)
            .description("How fast your camera turns to the place spot in Real rotation mode (higher = snappier).").group("General"));
    }

    @Override public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            releaseWalkKeys(mc);
            if (mc.player != null && prevSlot >= 0) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        prevSlot = -1;
        legitAim = null;
        surroundComplete = false;
        HeldSlotManager.clear(this);
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null || mc.gameMode == null || mc.gui.screen() != null) { releaseWalkKeys(mc); return; }

        if (!HeldSlotManager.holds(this) && prevSlot >= 0) {
            releaseWalkKeys(mc);
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            prevSlot = -1;
            return;
        }

        if (bool("autoDisableOnMove") && p.getDeltaMovement().horizontalDistanceSqr() > 0.01) { releaseWalkKeys(mc); return; }
        if (bool("onlyWhenThreatened") && !threatened(mc, p)) {
            releaseWalkKeys(mc); surroundComplete = false; legitAim = null;
            HeldSlotManager.release(this); restoreSlot(mc, p); return;
        }

        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_SURROUND);
        if (!HeldSlotManager.holds(this)) { releaseWalkKeys(mc); return; }

        boolean realRot = "Real".equals(choice("rotationMode"));

        if (realRot) easeLegit(p); else legitAim = null;

        if (bool("centerFirst")) {
            if (!centerByWalk(mc, p)) return;
        } else {
            releaseWalkKeys(mc);
        }

        if (!bool("replaceMined") && surroundComplete) {
            HeldSlotManager.release(this);
            restoreSlot(mc, p);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastPlaceMs < PvpUtil.jitterMs(integer("delay"))) return;

        List<BlockPos> targets = surroundPositions(p);
        Item block = blockItem();
        int placed = 0;
        int budget = integer("blocksPerTick");
        for (BlockPos pos : targets) {
            if (placed >= budget) break;
            if (!PvpUtil.isAirOrReplaceable(level, pos)) continue;
            BlockHitResult hit = PvpUtil.placeHitResult(level, pos);
            if (hit == null) continue;
            if (!ensureBlock(mc, p, block)) return;

            PvpUtil.ghostSafeUseOn(mc, p, hit);
            if (realRot) legitAim = hit.getLocation();
            placed++;
        }
        if (placed > 0) lastPlaceMs = now;
        else { surroundComplete = true; HeldSlotManager.release(this); restoreSlot(mc, p); }
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

    private void easeLegit(LocalPlayer p) {
        if (legitAim == null) return;
        double ease = decimal("legitEase");
        if (ease <= 0) ease = LEGIT_EASE;
        AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(p);
        AutismRotationUtil.Rotation target = AutismRotationUtil.lookingAt(legitAim, p.getEyePosition());
        AutismRotationUtil.Rotation eased = AutismRotationUtil.interpolate(cur, target, (float) ease);
        AutismRotationUtil.apply(p, AutismRotationUtil.normalizeToSensitivity(eased, cur), false);
    }

    private List<BlockPos> surroundPositions(LocalPlayer p) {
        BlockPos feet = p.blockPosition();
        List<BlockPos> out = new ArrayList<>();
        out.add(feet.north());
        out.add(feet.south());
        out.add(feet.east());
        out.add(feet.west());
        String expand = choice("expand");
        if ("Sides + Under".equals(expand)) {
            out.add(feet.below());
        } else if ("Two High".equals(expand)) {
            BlockPos head = feet.above();
            out.add(head.north());
            out.add(head.south());
            out.add(head.east());
            out.add(head.west());
        }
        return out;
    }

    private boolean threatened(Minecraft mc, LocalPlayer p) {
        double r = 8.0;
        for (Player pl : mc.level.players()) {
            if (pl == p || pl.isSpectator()) continue;
            if (PvpUtil.isFriend(pl, BossPvpAddon.friends()) || (bool("teamCheck") && PvpUtil.isTeammate(p, pl))) continue;
            if (pl.distanceToSqr(p) <= r * r) return true;
        }
        AABB box = p.getBoundingBox().inflate(r);
        return !mc.level.getEntitiesOfClass(EndCrystal.class, box, e -> e.isAlive()).isEmpty();
    }

    private Item blockItem() {
        return switch (choice("block")) {
            case "Crying Obsidian" -> Items.CRYING_OBSIDIAN;
            case "Cobblestone" -> Items.COBBLESTONE;
            default -> Items.OBSIDIAN;
        };
    }

    private boolean ensureBlock(Minecraft mc, LocalPlayer p, Item block) {
        if (p.getMainHandItem().is(block)) return true;
        for (int i = 0; i <= 8; i++) {
            if (p.getInventory().getItem(i).is(block)) {
                if (prevSlot < 0) prevSlot = p.getInventory().getSelectedSlot();
                AutismInventoryHelper.selectHotbarSlot(mc, i);
                return false;
            }
        }
        return false;
    }

    private void restoreSlot(Minecraft mc, LocalPlayer p) {
        if (prevSlot >= 0) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        prevSlot = -1;
    }
}
