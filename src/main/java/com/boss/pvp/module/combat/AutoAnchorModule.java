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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class AutoAnchorModule extends Module {

    private static final double ANCHOR_POWER = 5.0;
    private static final int CONFIRM_TICKS = 3;
    private static final int MAX_RETRIES = 5;
    private static final int UNSAFE_GIVEUP_TICKS = 40;

    private enum Phase { PLACE, CHARGE, DETONATE }

    private Phase phase = Phase.PLACE;
    private BlockPos anchorPos = null;
    private long stepSentTick = -1;
    private int stepRetries = 0;
    private int chargeAtSend = -1;
    private long unsafeWaitStart = -1;
    private long tickCounter = 0L;
    private int prevSlot = -1;
    private Vec3 legitAim = null;

    public AutoAnchorModule() {
        super(BossPvpAddon.ID + ":autoanchor", "AutoAnchor", "Place + charge + detonate respawn anchors: ghost-safe, confirm-sequenced, auto re-arm.");

        add(new DoubleSetting("range", "Target range", 6.0, 1.0, 12.0, 0.5).group("Target"));
        add(new BoolSetting("teamCheck", "Team check", false)
            .description("Skip players wearing leather armour dyed the same colour as yours (teammates).").group("Team"));
        add(new DoubleSetting("placeRange", "Place reach", 4.5, 1.0, 6.0, 0.5).group("Target"));
        add(new IntSetting("charges", "Charges before detonate", 1, 1, 4, 1).group("Actions"));

        add(new ChoiceSetting("rotationMode", "Rotation", "Silent", "Silent", "Real").group("Targeting"));
        add(new DoubleSetting("legitEase", "Legit ease speed", 0.25, 0.05, 1.0, 0.05)
            .description("How fast the real camera glides to the anchor in Legit mode (higher = snappier).").group("Targeting"));
        add(new ChoiceSetting("targetMode", "Place target", "Highest damage", "Highest damage", "Closest", "Safest").group("Targeting"));
        add(new BoolSetting("raytrace", "Raytrace", true).group("Targeting"));
        add(new BoolSetting("predict", "Predict (lead target)", false).group("Targeting"));
        add(new IntSetting("predictTicks", "Predict ticks", 3, 0, 10, 1).group("Targeting"));

        add(new BoolSetting("antiSuicide", "Anti-suicide", true).group("Safety"));
        add(new DoubleSetting("maxSelfDamage", "Max self damage", 8.0, 0.0, 20.0, 0.5).group("Safety"));
        add(new DoubleSetting("healthFloor", "Health floor", 6.0, 0.0, 20.0, 0.5)
            .description("Never detonate if it would drop your health below this.").group("Safety"));
        add(new DoubleSetting("minEnemyDamage", "Min enemy damage", 4.0, 0.0, 20.0, 0.5).group("Safety"));

        add(new BoolSetting("switchBack", "Switch back after", true).group("Switch"));
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && prevSlot >= 0 && bool("switchBack")) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        prevSlot = -1;
        legitAim = null;
        resetSequence();
        HeldSlotManager.clear(this);
    }

    public void tick(Minecraft mc) {
        tickCounter++;
        LocalPlayer me = mc.player;
        Level level = mc.level;
        if (me == null || level == null || mc.gameMode == null || mc.gui.screen() != null) return;

        if (!HeldSlotManager.holds(this) && prevSlot >= 0) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            prevSlot = -1;
            return;
        }

        if (level.dimension() == Level.NETHER) { abort(mc, me); return; }

        Player target = nearestEnemy(mc, me);
        if (target == null) { abort(mc, me); return; }

        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_AUTOANCHOR);
        if (!HeldSlotManager.holds(this)) return;

        boolean realRot = "Real".equals(choice("rotationMode"));

        if (anchorPos == null) phase = Phase.PLACE;
        switch (phase) {
            case PLACE    -> doPlace(mc, me, level, target);
            case CHARGE   -> doCharge(mc, me, level);
            case DETONATE -> doDetonate(mc, me, level);
        }

        if (realRot) easeLegit(me);
        else legitAim = null;
    }

    private void doPlace(Minecraft mc, LocalPlayer me, Level level, Player target) {
        if (anchorPos == null) {
            BlockPos base = bestBase(mc, me, target);
            if (base == null) return;
            anchorPos = base;
            beginStep();
        }
        BlockState st = level.getBlockState(anchorPos);
        if (st.getBlock() instanceof RespawnAnchorBlock) { phase = Phase.CHARGE; beginStep(); chargeAtSend = -1; return; }

        if (!PvpUtil.isAirOrReplaceable(level, anchorPos)) { anchorPos = null; beginStep(); return; }

        if (stepSentTick < 0) {
            if (sendPlace(mc, me, level, anchorPos)) stepSentTick = tickCounter;
        } else if (tickCounter - stepSentTick >= CONFIRM_TICKS) {
            if (stepRetries >= MAX_RETRIES) { anchorPos = null; beginStep(); }
            else if (sendPlace(mc, me, level, anchorPos)) { stepSentTick = tickCounter; stepRetries++; }
        }
    }

    private boolean sendPlace(Minecraft mc, LocalPlayer me, Level level, BlockPos base) {
        if (!hasItem(me, Items.RESPAWN_ANCHOR)) return false;
        if (!ensureAnchor(mc, me)) return false;
        BlockHitResult hit = PvpUtil.placeHitResult(level, base);
        if (hit == null) return false;
        if (bool("raytrace") && !PvpUtil.canSee(mc, me, me.getEyePosition(), hit.getLocation())) return false;
        legitAim = hit.getLocation();
        PvpUtil.ghostSafeUseOn(mc, me, hit);
        return true;
    }

    private void doCharge(Minecraft mc, LocalPlayer me, Level level) {
        BlockState st = level.getBlockState(anchorPos);
        if (!(st.getBlock() instanceof RespawnAnchorBlock)) { resetSequence(); return; }
        int charge = st.getValue(RespawnAnchorBlock.CHARGE);
        if (charge >= integer("charges")) { phase = Phase.DETONATE; beginStep(); return; }

        if (stepSentTick < 0) {
            if (sendCharge(mc, me)) { stepSentTick = tickCounter; chargeAtSend = charge; }
        } else if (charge > chargeAtSend) {
            beginStep(); chargeAtSend = -1;
        } else if (tickCounter - stepSentTick >= CONFIRM_TICKS) {
            if (stepRetries >= MAX_RETRIES) { resetSequence(); }
            else if (sendCharge(mc, me)) { stepSentTick = tickCounter; stepRetries++; chargeAtSend = charge; }
        }
    }

    private boolean sendCharge(Minecraft mc, LocalPlayer me) {
        if (!hasItem(me, Items.GLOWSTONE)) return false;
        if (!ensureHolding(mc, me, true)) return false;
        BlockHitResult hit = anchorHit();
        legitAim = hit.getLocation();
        PvpUtil.ghostSafeUseOn(mc, me, hit);
        return true;
    }

    private void doDetonate(Minecraft mc, LocalPlayer me, Level level) {
        BlockState st = level.getBlockState(anchorPos);
        if (!(st.getBlock() instanceof RespawnAnchorBlock)) { resetSequence(); return; }

        Vec3 center = Vec3.atCenterOf(anchorPos);
        if (!safeToDetonate(me, center)) {
            if (unsafeWaitStart < 0) unsafeWaitStart = tickCounter;
            if (tickCounter - unsafeWaitStart >= UNSAFE_GIVEUP_TICKS) resetSequence();
            return;
        }
        unsafeWaitStart = -1;

        if (stepSentTick < 0) {
            if (sendDetonate(mc, me)) stepSentTick = tickCounter;
        } else if (tickCounter - stepSentTick >= CONFIRM_TICKS) {
            if (stepRetries >= MAX_RETRIES) { resetSequence(); }
            else if (sendDetonate(mc, me)) { stepSentTick = tickCounter; stepRetries++; }
        }
    }

    private boolean sendDetonate(Minecraft mc, LocalPlayer me) {
        if (!ensureHolding(mc, me, false)) return false;
        BlockHitResult hit = anchorHit();
        legitAim = hit.getLocation();
        PvpUtil.ghostSafeUseOn(mc, me, hit);
        return true;
    }

    private boolean safeToDetonate(LocalPlayer me, Vec3 center) {
        if (!bool("antiSuicide")) return true;
        double self = estimateDamage(me.position(), center, ANCHOR_POWER);
        if (self > decimal("maxSelfDamage")) return false;
        double hp = me.getHealth() + me.getAbsorptionAmount();
        return hp - self >= decimal("healthFloor");
    }

    private BlockPos bestBase(Minecraft mc, LocalPlayer me, Player target) {
        Level level = mc.level;
        double reach = decimal("placeRange");
        int r = (int) Math.ceil(reach);
        Vec3 targetPos = predictedPos(target);
        BlockPos center = BlockPos.containing(targetPos);
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
                    if (bool("antiSuicide") && estimateDamage(me.position(), c, ANCHOR_POWER) > decimal("maxSelfDamage")) continue;
                    double enemyDmg = estimateDamage(targetPos, c, ANCHOR_POWER);
                    if (enemyDmg < decimal("minEnemyDamage")) continue;
                    if (bool("raytrace") && !PvpUtil.canSee(mc, me, me.getEyePosition(), c)) continue;
                    double score = switch (choice("targetMode")) {
                        case "Closest" -> -c.distanceToSqr(targetPos);
                        case "Safest" -> -estimateDamage(me.position(), c, ANCHOR_POWER);
                        default -> enemyDmg;
                    };
                    if (score > bestScore) { bestScore = score; best = pos; }
                }
            }
        }
        return best;
    }

    private Vec3 predictedPos(Player target) {
        if (!bool("predict")) return target.position();
        double t = integer("predictTicks");
        Vec3 v = target.getDeltaMovement();
        return target.position().add(v.x * t, 0.0, v.z * t);
    }

    private boolean hasItem(LocalPlayer me, net.minecraft.world.item.Item item) {
        if (me.getMainHandItem().is(item)) return true;
        for (int i = 0; i <= 8; i++) if (me.getInventory().getItem(i).is(item)) return true;
        return false;
    }

    private boolean ensureHolding(Minecraft mc, LocalPlayer me, boolean wantGlowstone) {
        boolean holdingGlowstone = me.getMainHandItem().is(Items.GLOWSTONE);
        if (wantGlowstone) {
            if (holdingGlowstone) return true;
            for (int i = 0; i <= 8; i++) {
                if (me.getInventory().getItem(i).is(Items.GLOWSTONE)) {
                    if (prevSlot < 0) prevSlot = me.getInventory().getSelectedSlot();
                    AutismInventoryHelper.selectHotbarSlot(mc, i);
                    return false;
                }
            }
            return false;
        }
        if (!holdingGlowstone) return true;
        if (prevSlot < 0) prevSlot = me.getInventory().getSelectedSlot();
        AutismInventoryHelper.selectHotbarSlot(mc, prevSlot >= 0 ? prevSlot : 0);
        return false;
    }

    private boolean ensureAnchor(Minecraft mc, LocalPlayer me) {
        if (me.getMainHandItem().is(Items.RESPAWN_ANCHOR)) return true;
        for (int i = 0; i <= 8; i++) {
            if (me.getInventory().getItem(i).is(Items.RESPAWN_ANCHOR)) {
                if (prevSlot < 0) prevSlot = me.getInventory().getSelectedSlot();
                AutismInventoryHelper.selectHotbarSlot(mc, i);
                return false;
            }
        }
        return false;
    }

    private void easeLegit(LocalPlayer me) {
        if (legitAim == null) return;
        AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(me);
        AutismRotationUtil.Rotation target = AutismRotationUtil.lookingAt(legitAim, me.getEyePosition());
        AutismRotationUtil.Rotation eased = AutismRotationUtil.interpolate(cur, target, (float) decimal("legitEase"));
        AutismRotationUtil.apply(me, AutismRotationUtil.normalizeToSensitivity(eased, cur), false);
    }

    private void beginStep() { stepSentTick = -1; stepRetries = 0; }

    private void resetSequence() {
        anchorPos = null;
        phase = Phase.PLACE;
        stepSentTick = -1;
        stepRetries = 0;
        chargeAtSend = -1;
        unsafeWaitStart = -1;
    }

    private BlockHitResult anchorHit() {
        return new BlockHitResult(Vec3.atCenterOf(anchorPos), Direction.UP, anchorPos, false);
    }

    private void abort(Minecraft mc, LocalPlayer me) {
        resetSequence();
        legitAim = null;
        HeldSlotManager.release(this);
        if (prevSlot >= 0 && bool("switchBack")) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        prevSlot = -1;
    }

    private Player nearestEnemy(Minecraft mc, LocalPlayer me) {
        Player best = null;
        double bestDist = decimal("range") * decimal("range");
        for (Player pl : mc.level.players()) {
            if (pl == me || pl.isSpectator()) continue;
            if (pl.getName().getString().equals(me.getName().getString())) continue;
            if (PvpUtil.isFriend(pl, BossPvpAddon.friends()) || (bool("teamCheck") && PvpUtil.isTeammate(me, pl))) continue;
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
