package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class TrapperModule extends Module {

    private static final float LEGIT_EASE = 0.25f;

    private long lastPlaceMs = 0L;
    private int prevSlot = -1;
    private Vec3 legitAim = null;

    public TrapperModule() {
        super(BossPvpAddon.ID + ":trapper", "Trapper", "Wall a nearby enemy in with obsidian to trap them.");

        add(new DoubleSetting("range", "Target range", 5.0, 1.0, 8.0, 0.5).group("General"));
        add(new DoubleSetting("placeRange", "Place reach", 4.5, 1.0, 6.0, 0.5).group("General"));
        add(new ChoiceSetting("block", "Block", "Obsidian", "Obsidian", "Crying Obsidian", "Cobblestone").group("General"));
        add(new BoolSetting("headRing", "Also wall the head", true).group("General"));
        add(new BoolSetting("onlyKillAuraTarget", "Only KillAura's target", false).group("General"));
        add(new BoolSetting("teamCheck", "Ignore teammates", false)
            .description("Skip players wearing leather armour dyed the same colour as yours (teammates).").group("Team"));
        add(new IntSetting("blocksPerTick", "Blocks per tick", 1, 1, 4, 1).group("General"));
        add(new IntSetting("delay", "Delay (ms)", 60, 0, 1000, 10).group("General"));
        add(new ChoiceSetting("rotationMode", "Rotation", "Silent", "Silent", "Real").group("General"));
        add(new DoubleSetting("legitEase", "Camera turn speed", 0.25, 0.05, 1.0, 0.05)
            .description("How fast your camera turns to the place spot in Real rotation mode (higher = snappier).").group("General"));
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

        Player target = pickTarget(mc, p);
        if (target == null) { legitAim = null; HeldSlotManager.release(this); restoreSlot(mc, p); return; }

        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_TRAPPER);
        if (!HeldSlotManager.holds(this)) return;

        boolean realRot = "Real".equals(choice("rotationMode"));

        if (realRot) easeLegit(p); else legitAim = null;

        long now = System.currentTimeMillis();
        if (now - lastPlaceMs < PvpUtil.jitterMs(integer("delay"))) return;

        Item block = blockItem();
        double reachSq = decimal("placeRange") * decimal("placeRange");
        int budget = integer("blocksPerTick");
        int placed = 0;
        for (BlockPos pos : trapPositions(target)) {
            if (placed >= budget) break;
            if (!PvpUtil.isAirOrReplaceable(level, pos)) continue;
            if (Vec3.atCenterOf(pos).distanceToSqr(p.getEyePosition()) > reachSq) continue;
            BlockHitResult hit = PvpUtil.placeHitResult(level, pos);
            if (hit == null) continue;
            if (!ensureBlock(mc, p, block)) return;

            PvpUtil.ghostSafeUseOn(mc, p, hit);
            if (realRot) legitAim = hit.getLocation();
            placed++;
        }
        if (placed > 0) lastPlaceMs = now;
        else { HeldSlotManager.release(this); restoreSlot(mc, p); }
    }

    private Player pickTarget(Minecraft mc, LocalPlayer p) {
        if (bool("onlyKillAuraTarget")) {
            if (BossPvpAddon.killAura != null && BossPvpAddon.killAura.isEnabled()
                && BossPvpAddon.killAura.currentTarget() instanceof Player pl
                && !(PvpUtil.isFriend(pl, BossPvpAddon.friends()) || (bool("teamCheck") && PvpUtil.isTeammate(p, pl)))) return pl;
            return null;
        }
        Player best = null;
        double bestD = decimal("range") * decimal("range");
        for (Player pl : mc.level.players()) {
            if (pl == p || pl.isSpectator()) continue;
            if (PvpUtil.isFriend(pl, BossPvpAddon.friends()) || (bool("teamCheck") && PvpUtil.isTeammate(p, pl))) continue;
            double d = pl.distanceToSqr(p);
            if (d < bestD) { bestD = d; best = pl; }
        }
        return best;
    }

    private List<BlockPos> trapPositions(Player target) {
        BlockPos feet = target.blockPosition();
        List<BlockPos> out = new ArrayList<>();
        for (Direction d : Direction.Plane.HORIZONTAL) out.add(feet.relative(d));
        if (bool("headRing")) {
            BlockPos head = feet.above();
            for (Direction d : Direction.Plane.HORIZONTAL) out.add(head.relative(d));
        }
        return out;
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

    private void easeLegit(LocalPlayer p) {
        if (legitAim == null) return;
        double ease = decimal("legitEase");
        if (ease <= 0) ease = LEGIT_EASE;
        AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(p);
        AutismRotationUtil.Rotation target = AutismRotationUtil.lookingAt(legitAim, p.getEyePosition());
        AutismRotationUtil.Rotation eased = AutismRotationUtil.interpolate(cur, target, (float) ease);
        AutismRotationUtil.apply(p, AutismRotationUtil.normalizeToSensitivity(eased, cur), false);
    }
}
