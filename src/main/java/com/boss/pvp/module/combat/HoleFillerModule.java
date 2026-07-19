package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.MenuMode;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.input.HeldSlotManager;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismInventoryHelper;
import autismclient.util.AutismRotationUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class HoleFillerModule extends Module {

    private static final float LEGIT_EASE = 0.25f;

    private long lastPlaceMs = 0L;
    private int prevSlot = -1;
    private Vec3 legitAim = null;

    public HoleFillerModule() {
        super(BossPvpAddon.ID + ":holefiller", "HoleFiller", "Fills the hole an enemy hides in.");

        add(new DoubleSetting("range", "Reach", 4.5, 1.0, 6.0, 0.5).group("General"));
        add(new BoolSetting("onlyWithKillAura", "Only KillAura's target", true).group("General"));
        add(new BoolSetting("teamCheck", "Ignore teammates", false)
            .description("Skip players wearing leather armour dyed the same colour as yours (teammates).").visibleWhen(MenuMode::advanced).group("Team"));
        add(new ChoiceSetting("block", "Block", "Obsidian", "Obsidian", "Crying Obsidian", "Cobblestone").group("General"));
        add(new IntSetting("blocksPerTick", "Blocks per tick", 1, 1, 4, 1).visibleWhen(MenuMode::advanced).group("General"));
        add(new IntSetting("delay", "Delay (ms)", 80, 0, 1000, 10).visibleWhen(MenuMode::advanced).group("General"));

        add(new BoolSetting("prediction", "Predict movement", true).visibleWhen(MenuMode::advanced).group("Targeting"));
        add(new DoubleSetting("predictionStrength", "Prediction strength", 0.5, 0.0, 3.0, 0.1).visibleWhen(MenuMode::advanced).group("Targeting"));
        add(new BoolSetting("raytrace", "Only visible spots", true).visibleWhen(MenuMode::advanced).group("Targeting"));
        add(new ChoiceSetting("rotationMode", "Rotation", "Silent", "Silent", "Real").visibleWhen(MenuMode::advanced).group("Targeting"));
        add(new DoubleSetting("legitEase", "Camera turn speed", 0.25, 0.05, 1.0, 0.05)
            .description("How fast your camera turns to the place spot in Real rotation mode (higher = snappier).").visibleWhen(MenuMode::advanced).group("Targeting"));
    }

    @Override
    public void onDisable() {

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && prevSlot >= 0) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        prevSlot = -1;
        HeldSlotManager.clear(this);
    }

    public void tick(Minecraft mc) {
        LocalPlayer me = mc.player;
        Level level = mc.level;
        if (me == null || level == null || mc.gameMode == null || mc.gui.screen() != null) return;

        if (!HeldSlotManager.holds(this) && prevSlot >= 0) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            prevSlot = -1;
            return;
        }

        LivingEntity target = pickTarget(mc, me);

        if (target == null || (target instanceof Player tp && (PvpUtil.isFriend(tp, BossPvpAddon.friends()) || (bool("teamCheck") && PvpUtil.isTeammate(me, tp))))) {
            legitAim = null; HeldSlotManager.release(this); restoreSlot(mc, me); return;
        }

        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_HOLEFILLER);
        if (!HeldSlotManager.holds(this)) return;

        boolean realRot = "Real".equals(choice("rotationMode"));

        if (realRot) easeLegit(me); else legitAim = null;

        long now = System.currentTimeMillis();
        if (now - lastPlaceMs < PvpUtil.jitterMs(integer("delay"))) return;

        BlockPos feet = predictedFeet(target);
        if (!enclosed(level, feet)) { HeldSlotManager.release(this); restoreSlot(mc, me); return; }

        BlockPos cap = feet.above(2);
        if (!PvpUtil.isAirOrReplaceable(level, cap)) { HeldSlotManager.release(this); restoreSlot(mc, me); return; }
        Vec3 c = Vec3.atCenterOf(cap);
        if (c.distanceToSqr(me.position()) > decimal("range") * decimal("range")) { HeldSlotManager.release(this); restoreSlot(mc, me); return; }
        if (bool("raytrace") && !PvpUtil.canSee(mc, me, me.getEyePosition(), c)) { HeldSlotManager.release(this); restoreSlot(mc, me); return; }
        BlockHitResult hit = PvpUtil.placeHitResult(level, cap);
        if (hit == null) { HeldSlotManager.release(this); restoreSlot(mc, me); return; }

        int budget = integer("blocksPerTick");
        int placed = 0;
        if (placed < budget) {
            if (!ensureBlock(mc, me, blockItem())) return;

            PvpUtil.ghostSafeUseOn(mc, me, hit);
            if (realRot) legitAim = hit.getLocation();
            placed++;
            lastPlaceMs = now;
        }
    }

    private LivingEntity pickTarget(Minecraft mc, LocalPlayer me) {
        if (bool("onlyWithKillAura")) {
            if (BossPvpAddon.killAura != null && BossPvpAddon.killAura.isEnabled()) {
                return BossPvpAddon.killAura.currentTarget();
            }
            return null;
        }
        Player best = null;
        double bestDist = 36.0;
        for (Player pl : mc.level.players()) {
            if (pl == me || pl.isSpectator()) continue;
            if (PvpUtil.isFriend(pl, BossPvpAddon.friends()) || (bool("teamCheck") && PvpUtil.isTeammate(me, pl))) continue;
            double d = pl.distanceToSqr(me);
            if (d < bestDist) { bestDist = d; best = pl; }
        }
        return best;
    }

    private BlockPos predictedFeet(LivingEntity target) {
        Vec3 pos = target.position();
        if (bool("prediction")) {
            Vec3 v = target.getDeltaMovement();
            double s = decimal("predictionStrength");
            pos = pos.add(v.x * s, 0, v.z * s);
        }
        return BlockPos.containing(pos.x, target.getY(), pos.z);
    }

    private boolean enclosed(Level level, BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(feet.relative(d)).isAir()) return false;
        }
        return true;
    }

    private Item blockItem() {
        return switch (choice("block")) {
            case "Crying Obsidian" -> Items.CRYING_OBSIDIAN;
            case "Cobblestone" -> Items.COBBLESTONE;
            default -> Items.OBSIDIAN;
        };
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

    private void easeLegit(LocalPlayer me) {
        if (legitAim == null) return;
        double ease = decimal("legitEase");
        if (ease <= 0) ease = LEGIT_EASE;
        AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(me);
        AutismRotationUtil.Rotation target = AutismRotationUtil.lookingAt(legitAim, me.getEyePosition());
        AutismRotationUtil.Rotation eased = AutismRotationUtil.interpolate(cur, target, (float) ease);
        AutismRotationUtil.apply(me, AutismRotationUtil.normalizeToSensitivity(eased, cur), false);
    }
}
