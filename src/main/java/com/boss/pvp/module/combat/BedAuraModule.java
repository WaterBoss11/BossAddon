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
import net.minecraft.world.item.BedItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class BedAuraModule extends Module {

    private static final double BED_POWER = 5.0;
    private static final float LEGIT_EASE = 0.25f;

    private long lastMs = 0L;
    private BlockPos bedPos = null;
    private int prevSlot = -1;
    private Vec3 legitAim = null;

    public BedAuraModule() {
        super(BossPvpAddon.ID + ":bedaura", "BedAura", "Place + detonate beds on the target (Nether/End).");

        add(new DoubleSetting("range", "Target range", 6.0, 1.0, 12.0, 0.5).group("Target"));
        add(new DoubleSetting("placeRange", "Place reach", 4.5, 1.0, 6.0, 0.5).group("Target"));
        add(new IntSetting("delay", "Action delay (ms)", 150, 0, 1000, 10).group("Actions"));

        add(new ChoiceSetting("rotationMode", "Rotation", "Silent", "Silent", "Real").group("Targeting"));
        add(new DoubleSetting("legitEase", "Legit ease speed", 0.25, 0.05, 1.0, 0.05)
            .description("How fast the real camera glides to the place/detonate point in Real mode (higher = snappier).").group("Targeting"));
        add(new ChoiceSetting("targetMode", "Place target", "Highest damage", "Highest damage", "Closest", "Safest").group("Targeting"));
        add(new BoolSetting("raytrace", "Raytrace", true).group("Targeting"));

        add(new BoolSetting("antiSuicide", "Anti-suicide", true).group("Safety"));
        add(new DoubleSetting("maxSelfDamage", "Max self damage", 8.0, 0.0, 20.0, 0.5).group("Safety"));
        add(new DoubleSetting("minEnemyDamage", "Min enemy damage", 4.0, 0.0, 20.0, 0.5).group("Safety"));

        add(new BoolSetting("switchBack", "Switch back after", true).group("Switch"));

        add(new BoolSetting("teamCheck", "Team check", false)
            .description("Skip players wearing leather armour dyed the same colour as yours (teammates).").group("Team"));
    }

    @Override
    public void onDisable() {

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && prevSlot >= 0 && bool("switchBack")) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        bedPos = null;
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
            bedPos = null;
            return;
        }

        if (level.dimension() != Level.NETHER && level.dimension() != Level.END) { abort(mc, me); return; }

        Player target = nearestEnemy(mc, me);
        if (target == null) { legitAim = null; abort(mc, me); return; }

        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_BEDAURA);
        if (!HeldSlotManager.holds(this)) return;

        boolean realRot = "Real".equals(choice("rotationMode"));

        if (realRot) easeLegit(me); else legitAim = null;

        long now = System.currentTimeMillis();
        if (now - lastMs < PvpUtil.jitterMs(integer("delay"))) return;

        if (bedPos != null) {
            BlockState st = level.getBlockState(bedPos);
            if (!(st.getBlock() instanceof BedBlock)) { bedPos = null; return; }
            Vec3 center = Vec3.atCenterOf(bedPos);
            if (bool("antiSuicide") && estimateDamage(me.position(), center, BED_POWER) > decimal("maxSelfDamage")) return;
            if (!ensureNonBed(mc, me)) return;

            BlockHitResult dHit = new BlockHitResult(center, Direction.UP, bedPos, false);
            PvpUtil.ghostSafeUseOn(mc, me, dHit);
            if (realRot) legitAim = center;
            bedPos = null;
            restoreSlot(mc, me);
            lastMs = now;
            return;
        }

        BlockPos base = bestBase(mc, me, target);
        if (base == null) return;
        if (!ensureBed(mc, me)) return;
        BlockHitResult hit = PvpUtil.placeHitResult(level, base);
        if (hit == null) return;

        PvpUtil.ghostSafeUseOn(mc, me, hit);
        if (realRot) legitAim = hit.getLocation();
        bedPos = findBedNear(level, base);
        lastMs = now;
    }

    private BlockPos bestBase(Minecraft mc, LocalPlayer me, Player target) {
        Level level = mc.level;
        double reach = decimal("placeRange");
        int r = (int) Math.ceil(reach);
        BlockPos center = target.blockPosition();
        BlockPos best = null;
        double bestScore = -Double.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!PvpUtil.isAirOrReplaceable(level, pos)) continue;
                    if (PvpUtil.placeHitResult(level, pos) == null) continue;
                    Vec3 c = Vec3.atCenterOf(pos);
                    if (c.distanceToSqr(me.position()) > reach * reach) continue;
                    if (bool("antiSuicide") && estimateDamage(me.position(), c, BED_POWER) > decimal("maxSelfDamage")) continue;
                    double enemyDmg = estimateDamage(target.position(), c, BED_POWER);
                    if (enemyDmg < decimal("minEnemyDamage")) continue;
                    if (bool("raytrace") && !PvpUtil.canSee(mc, me, me.getEyePosition(), c)) continue;
                    double score = switch (choice("targetMode")) {
                        case "Closest" -> -c.distanceToSqr(target.position());
                        case "Safest" -> -estimateDamage(me.position(), c, BED_POWER);
                        default -> enemyDmg;
                    };
                    if (score > bestScore) { bestScore = score; best = pos; }
                }
            }
        }
        return best;
    }

    private BlockPos findBedNear(Level level, BlockPos base) {
        if (level.getBlockState(base).getBlock() instanceof BedBlock) return base;
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos n = base.relative(d);
            if (level.getBlockState(n).getBlock() instanceof BedBlock) return n;
        }
        return null;
    }

    private boolean ensureBed(Minecraft mc, LocalPlayer me) {
        if (me.getMainHandItem().getItem() instanceof BedItem) return true;
        for (int i = 0; i <= 8; i++) {
            if (me.getInventory().getItem(i).getItem() instanceof BedItem) {
                if (prevSlot < 0) prevSlot = me.getInventory().getSelectedSlot();
                AutismInventoryHelper.selectHotbarSlot(mc, i);
                return false;
            }
        }
        return false;
    }

    private boolean ensureNonBed(Minecraft mc, LocalPlayer me) {
        if (!(me.getMainHandItem().getItem() instanceof BedItem)) return true;
        AutismInventoryHelper.selectHotbarSlot(mc, prevSlot >= 0 ? prevSlot : 0);
        return false;
    }

    private void restoreSlot(Minecraft mc, LocalPlayer me) {
        if (prevSlot >= 0 && bool("switchBack")) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        prevSlot = -1;
    }

    private void abort(Minecraft mc, LocalPlayer me) {
        bedPos = null;
        HeldSlotManager.release(this);
        restoreSlot(mc, me);
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

    private Player nearestEnemy(Minecraft mc, LocalPlayer me) {
        Player best = null;
        double bestDist = decimal("range") * decimal("range");
        for (Player pl : mc.level.players()) {
            if (pl == me || pl.isSpectator()) continue;
            if (pl.getName().getString().equals(me.getName().getString())) continue;
            if (bool("teamCheck") && PvpUtil.isTeammate(me, pl)) continue;
            double d = pl.distanceToSqr(me);
            if (d < bestDist) { bestDist = d; best = pl; }
        }
        return best;
    }

    private double estimateDamage(Vec3 entityPos, Vec3 sourcePos, double power) {
        double radius = 2.0 * power;
        double dist = entityPos.distanceTo(sourcePos);
        if (dist > radius) return 0.0;
        double impact = 1.0 - (dist / radius);
        return (impact * impact + impact) / 2.0 * 7.0 * power + 1.0;
    }
}
