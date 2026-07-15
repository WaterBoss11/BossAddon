package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.DamageUtil;
import com.boss.pvp.util.pvp.PlayerSimulation;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import com.boss.pvp.util.input.HeldSlotManager;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismInventoryHelper;
import autismclient.util.AutismRotationUtil;
import autismclient.util.AutismClientMessaging;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class AutoCrystalModule extends Module {

    private static final float LEGIT_EASE = 0.25f;
    private static final double EXPLOSION_RANGE = 12.0;
    private static final EquipmentSlot[] ARMOR_SLOTS =
        { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    private long lastPlaceMs = 0L;
    private long lastBreakMs = 0L;
    private long lastFastBreakTick = Long.MIN_VALUE;
    private int prevSlot = -1;
    private Vec3 legitAim = null;

    private final Map<Integer, Long> recentlyHit = new HashMap<>();

    private DamageSource explosionSource;

    private record Candidate(BlockPos base, double enemyDmg, double selfDmg, double distSq) {}

    public AutoCrystalModule() {
        super(BossPvpAddon.ID + ":autocrystal", "AutoCrystal", "Crystal aura: LiquidBounce-style damage, placement, anti-suicide and silent rotation.");

        add(new DoubleSetting("range", "Target range", 6.0, 1.0, 12.0, 0.5).group("Target"));
        add(new DoubleSetting("placeRange", "Place reach", 4.5, 1.0, 6.0, 0.5).group("Target"));
        add(new DoubleSetting("breakRange", "Break reach", 4.5, 1.0, 6.0, 0.5).group("Target"));

        add(new BoolSetting("doPlace", "Place", true).group("Actions"));
        add(new BoolSetting("doBreak", "Break", true).group("Actions"));
        add(new IntSetting("maxPerTick", "Max break / tick", 1, 1, 4, 1).group("Actions"));
        add(new IntSetting("maxPlace", "Max place / tick", 1, 1, 4, 1).group("Actions"));
        add(new ChoiceSetting("breakMode", "Break mode", "Normal", "Normal", "Packet").group("Actions"));
        add(new IntSetting("placeDelay", "Place delay (ms)", 100, 0, 1000, 10).group("Actions"));
        add(new IntSetting("breakDelay", "Break delay (ms)", 80, 0, 1000, 10).group("Actions"));
        add(new BoolSetting("fastBreak", "Fast break", false)
            .description("Break a crystal the instant it spawns (entity-load hook), rate-limited to 1 per 2 ticks. Off = unchanged.").group("Actions"));
        add(new BoolSetting("setDead", "Instant break (setDead)", true)
            .description("Don't re-attack a crystal we just hit until the server confirms it (LiquidBounce SetDead).").group("Actions"));

        add(new ChoiceSetting("rotationMode", "Rotation", "Silent", "Silent", "Real").group("Targeting"));
        add(new DoubleSetting("legitEase", "Legit ease speed", 0.25, 0.05, 1.0, 0.05)
            .description("How fast the real camera glides to the place/break point in Real mode (higher = snappier).").group("Targeting"));
        add(new ChoiceSetting("targetMode", "Place priority", "Highest damage", "Highest damage", "Closest", "Safest").group("Targeting"));
        add(new BoolSetting("prediction", "Prediction", true).group("Targeting"));
        add(new BoolSetting("physicsPredict", "Physics prediction", false)
            .description("Bias placement by a 2-tick vanilla-physics prediction instead of the velocity lead (off = unchanged).").group("Targeting"));
        add(new DoubleSetting("predictionStrength", "Prediction strength", 0.5, 0.0, 3.0, 0.1).group("Targeting"));
        add(new BoolSetting("raytrace", "Raytrace place + break", true).group("Targeting"));

        add(new BoolSetting("antiSuicide", "Anti-suicide", true)
            .description("Never place/break a crystal that would kill you (self damage >= health + absorption).").group("Safety"));
        add(new DoubleSetting("maxSelfDamage", "Max self damage", 8.0, 0.0, 20.0, 0.5).group("Safety"));
        add(new DoubleSetting("minEnemyDamage", "Min damage to place", 4.0, 0.0, 20.0, 0.5).group("Safety"));
        add(new DoubleSetting("minBreakDamage", "Min damage to break", 4.0, 0.0, 20.0, 0.5).group("Safety"));
        add(new BoolSetting("efficient", "Efficient (target > self)", true)
            .description("Only act when the crystal hurts the target more than it hurts you.").group("Safety"));
        add(new DoubleSetting("facePlaceHealth", "FacePlace below HP", 8.0, 0.0, 36.0, 1.0)
            .description("Below this target health, place even under the min-damage floor (finish a low enemy).").group("Safety"));

        add(new BoolSetting("autoSwitch", "Switch to crystal", true).group("Switch"));
        add(new BoolSetting("switchBack", "Switch back after", true).group("Switch"));
        add(new BoolSetting("placeAlert", "Chat ping on place", false).group("Switch"));

        add(new BoolSetting("teamCheck", "Team check", false)
            .description("Skip players wearing leather armour dyed the same colour as yours (teammates).").group("Team"));

        ClientEntityEvents.ENTITY_LOAD.register(this::onCrystalSpawn);
    }

    @Override
    public void onDisable() {

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && prevSlot >= 0 && bool("switchBack")) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        prevSlot = -1;
        legitAim = null;
        recentlyHit.clear();
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

        Player target = nearestEnemy(mc, me);
        if (target == null) { legitAim = null; HeldSlotManager.release(this); restoreSlot(mc, me); return; }

        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_AUTOCRYSTAL);
        if (!HeldSlotManager.holds(this)) return;

        long now = System.currentTimeMillis();
        explosionSource = level.damageSources().explosion(null, null);
        pruneRecentlyHit(now);

        int budget = integer("maxPerTick");
        boolean realRot = "Real".equals(choice("rotationMode"));

        if (realRot) easeLegit(me); else legitAim = null;

        if (bool("doBreak") && now - lastBreakMs >= PvpUtil.jitterMs(integer("breakDelay"))) {
            int done = 0;
            for (EndCrystal crystal : crystalsNear(mc, target.position(), decimal("breakRange"))) {
                if (done >= budget) break;
                if (bool("setDead") && recentlyHit.containsKey(crystal.getId())) continue;
                Vec3 cpos = crystal.position();
                double selfDmg = crystalDamage(me, cpos);
                double enemyDmg = crystalDamage(target, cpos);
                if (!safeToAct(me, selfDmg, enemyDmg, decimal("minBreakDamage"), false)) continue;
                if (bool("raytrace") && !PvpUtil.canSee(mc, me, me.getEyePosition(), cpos)) continue;

                PvpUtil.ghostSafeAttack(mc, me, crystal, cpos, !"Packet".equals(choice("breakMode")));
                if (bool("setDead")) recentlyHit.put(crystal.getId(), now);
                if (realRot) legitAim = cpos;
                lastBreakMs = now;
                done++;
            }
            if (done > 0) return;
        }

        if (bool("doPlace") && now - lastPlaceMs >= PvpUtil.jitterMs(integer("placeDelay"))) {
            List<Candidate> bases = bestBases(mc, me, target, integer("maxPlace"));
            if (bases.isEmpty()) return;
            if (!ensureCrystal(mc, me)) return;
            int placed = 0;
            for (Candidate c : bases) {
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(c.base()).add(0, 0.5, 0), Direction.UP, c.base(), false);

                PvpUtil.ghostSafeUseOn(mc, me, hit);
                if (realRot) legitAim = hit.getLocation();
                placed++;
            }
            if (placed > 0) {
                lastPlaceMs = now;
                if (bool("placeAlert")) AutismClientMessaging.sendPrefixed("§d[AutoCrystal] placed x" + placed);
            }
        }
    }

    private List<Candidate> bestBases(Minecraft mc, LocalPlayer me, Player target, int n) {
        Level level = mc.level;
        boolean facePlace = target.getHealth() <= (float) decimal("facePlaceHealth");
        double reach = decimal("placeRange");
        double reachSq = reach * reach;
        double minEnemy = decimal("minEnemyDamage");
        int r = (int) Math.ceil(reach);
        Vec3 eyes = me.getEyePosition();
        double targetHeadY = target.getBoundingBox().maxY;
        BlockPos center = BlockPos.containing(predicted(target));

        List<Candidate> valid = new ArrayList<>();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    BlockPos base = center.offset(dx, dy, dz);
                    var block = level.getBlockState(base).getBlock();
                    if (block != Blocks.OBSIDIAN && block != Blocks.BEDROCK) continue;
                    if (!level.getBlockState(base.above()).isAir()) continue;

                    if (base.getY() + 1 >= targetHeadY && !facePlace) continue;

                    Vec3 crystalPos = new Vec3(base.getX() + 0.5, base.getY() + 1.0, base.getZ() + 0.5);
                    double distSq = crystalPos.distanceToSqr(eyes);
                    if (distSq > reachSq) continue;

                    if (crystalBoxObstructed(level, base)) continue;

                    if (!facePlace && maxPossibleDamage(target, crystalPos) < minEnemy) continue;

                    double enemyDmg = crystalDamage(target, crystalPos);
                    double selfDmg = crystalDamage(me, crystalPos);
                    if (!safeToAct(me, selfDmg, enemyDmg, minEnemy, facePlace)) continue;
                    if (bool("raytrace") && !PvpUtil.canSee(mc, me, eyes, crystalPos)) continue;

                    valid.add(new Candidate(base, enemyDmg, selfDmg, distSq));
                }
            }
        }

        valid.sort(this::compareCandidates);
        return valid.size() > n ? new ArrayList<>(valid.subList(0, Math.max(0, n))) : valid;
    }

    private int compareCandidates(Candidate a, Candidate b) {
        switch (choice("targetMode")) {
            case "Closest" -> {
                int c = Double.compare(a.distSq(), b.distSq());
                if (c != 0) return c;
                c = Double.compare(b.enemyDmg(), a.enemyDmg());
                return c != 0 ? c : Double.compare(a.selfDmg(), b.selfDmg());
            }
            case "Safest" -> {
                int c = Double.compare(a.selfDmg(), b.selfDmg());
                if (c != 0) return c;
                c = Double.compare(b.enemyDmg(), a.enemyDmg());
                return c != 0 ? c : Double.compare(a.distSq(), b.distSq());
            }
            default -> {
                int c = Double.compare(b.enemyDmg(), a.enemyDmg());
                if (c != 0) return c;
                c = Double.compare(a.selfDmg(), b.selfDmg());
                return c != 0 ? c : Double.compare(a.distSq(), b.distSq());
            }
        }
    }

    private boolean safeToAct(LocalPlayer me, double selfDmg, double enemyDmg, double minEnemy, boolean facePlace) {
        if (!facePlace && enemyDmg < minEnemy) return false;
        if (bool("antiSuicide") && DamageUtil.wouldSelfKill(selfDmg, me.getHealth(), me.getAbsorptionAmount())) return false;
        if (selfDmg > decimal("maxSelfDamage")) return false;
        if (bool("efficient") && enemyDmg <= selfDmg) return false;
        return true;
    }

    private boolean crystalBoxObstructed(Level level, BlockPos base) {
        AABB box = new AABB(base.above());
        List<LivingEntity> hits = level.getEntitiesOfClass(LivingEntity.class, box, e -> e.isAlive());
        return !hits.isEmpty();
    }

    private Vec3 predicted(Player target) {

        if (bool("physicsPredict")) return PlayerSimulation.predictPosition(target, 2);
        if (!bool("prediction")) return target.position();
        return target.position().add(target.getDeltaMovement().scale(decimal("predictionStrength")));
    }

    private boolean ensureCrystal(Minecraft mc, LocalPlayer me) {
        if (me.getMainHandItem().is(Items.END_CRYSTAL)) return true;
        if (!bool("autoSwitch")) return false;
        for (int i = 0; i <= 8; i++) {
            if (me.getInventory().getItem(i).is(Items.END_CRYSTAL)) {
                if (prevSlot < 0) prevSlot = me.getInventory().getSelectedSlot();
                AutismInventoryHelper.selectHotbarSlot(mc, i);
                return false;
            }
        }
        return false;
    }

    private void restoreSlot(Minecraft mc, LocalPlayer me) {
        if (prevSlot >= 0 && bool("switchBack")) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        prevSlot = -1;
    }

    private double crystalDamage(LivingEntity entity, Vec3 crystalPos) {
        return DamageUtil.crystalDamage(entity.level(), crystalPos, entity, explosionSource);
    }

    private double maxPossibleDamage(LivingEntity entity, Vec3 crystalPos) {
        double distSq = entity.distanceToSqr(crystalPos);
        double raw = DamageUtil.rawExplosionDamage(1.0, Math.sqrt(distSq), DamageUtil.CRYSTAL_POWER);
        if (raw <= 0.0) return 0.0;
        return DamageUtil.effectiveDamage(entity, raw, explosionSource);
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

    private List<EndCrystal> crystalsNear(Minecraft mc, Vec3 around, double range) {
        AABB box = new AABB(around.x - range, around.y - range, around.z - range,
                            around.x + range, around.y + range, around.z + range);
        return mc.level.getEntitiesOfClass(EndCrystal.class, box, e -> e.isAlive());
    }

    private void onCrystalSpawn(Entity entity, ClientLevel world) {
        if (!isEnabled() || !bool("fastBreak") || !bool("doBreak")) return;
        if (!(entity instanceof EndCrystal crystal)) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer me = mc.player;
        if (me == null || mc.level == null || mc.gameMode == null || world != mc.level) return;

        long gt = mc.level.getGameTime();
        if (gt - lastFastBreakTick < 2) return;
        if (recentlyHit.containsKey(crystal.getId())) return;
        if (System.currentTimeMillis() - lastPlaceMs < 60) return;

        Vec3 cpos = crystal.position();
        double breakRange = decimal("breakRange");
        if (cpos.distanceToSqr(me.getEyePosition()) > breakRange * breakRange) return;
        if (bool("raytrace") && !PvpUtil.canSee(mc, me, me.getEyePosition(), cpos)) return;

        Player target = nearestEnemy(mc, me);
        if (target == null) return;

        explosionSource = mc.level.damageSources().explosion(null, null);
        double selfDmg = crystalDamage(me, cpos);
        double enemyDmg = crystalDamage(target, cpos);
        if (bool("antiSuicide") && DamageUtil.wouldSelfKill(selfDmg, me.getHealth(), me.getAbsorptionAmount())) return;
        if (selfDmg > decimal("maxSelfDamage")) return;
        if (enemyDmg < decimal("minBreakDamage")) return;

        PvpUtil.ghostSafeAttack(mc, me, crystal, cpos, !"Packet".equals(choice("breakMode")));
        if (bool("setDead")) recentlyHit.put(crystal.getId(), System.currentTimeMillis());
        lastFastBreakTick = gt;
    }

    private void pruneRecentlyHit(long now) {
        if (recentlyHit.isEmpty()) return;
        long window = Math.max(300L, integer("breakDelay") * 3L);
        recentlyHit.values().removeIf(t -> now - t > window);
    }
}
