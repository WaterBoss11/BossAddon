package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.DamageUtil;
import com.boss.pvp.util.pvp.CrystalHideManager;
import com.boss.pvp.util.pvp.CrystalActions;
import com.boss.pvp.util.pvp.ActionRateLimiter;
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
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
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

    // Keep BossPvpAddon.crystalActive (which suppresses KillAura) true for this long after the last
    // place/break so KillAura stays out for the whole place->break->confirm cycle, not just one tick.
    private static final long CRYSTAL_HOLD_MS = 300L;

    private long lastPlaceMs = 0L;
    private long lastBreakMs = 0L;
    private long lastCrystalActMs = 0L;
    private int prevSlot = -1;
    private Vec3 legitAim = null;

    private final Map<Integer, Long> recentlyHit = new HashMap<>();
    // One shared per-second cap across the tick-loop break and the crystal-spawn fast-break path.
    private final ActionRateLimiter breakRate = new ActionRateLimiter();

    // Set by the packet triggers (network thread) to make the next client tick act without waiting out the delay.
    private volatile boolean forceAct = false;

    private DamageSource explosionSource;

    private record Candidate(BlockPos base, double enemyDmg, double selfDmg, double distSq) {}

    public AutoCrystalModule() {
        super(BossPvpAddon.ID + ":autocrystal", "AutoCrystal", "Crystal aura: LiquidBounce-style damage, placement, anti-suicide and silent rotation.");

        add(RegistryListSetting.entityTypes("entities", "Entities", PvpUtil.DEFAULT_COMBAT_TARGETS).group("Target"));
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
        add(new BoolSetting("fastBreak", "Fast break", true)
            .description("Break a crystal the instant it spawns (entity-load hook), capped by Max breaks/sec. Raises cycle rate.").group("Actions"));
        add(new IntSetting("maxBps", "Max breaks / sec", 20, 1, 40, 1)
            .description("Upper bound on break attempts per second, shared by the normal loop and Fast break. Higher = faster cycle, but more suspicious to anticheat.").group("Actions"));
        add(new BoolSetting("setDead", "Instant break (setDead)", true)
            .description("Don't re-attack a crystal we just hit until the server confirms it (LiquidBounce SetDead).").group("Actions"));
        add(new BoolSetting("hideOnHit", "Client-side hide (HCsCR)", false)
            .description("The instant we hit a crystal (before the server confirms), drop its hitbox client-side so the spot frees for the next placement — the corrected obstruction check re-places there without waiting for the server. Reappears if the server never confirms (see Resync window). Prototype — verify per server.").group("Actions"));
        add(new IntSetting("resyncTicks", "Resync window", 4, 1, 50, 1)
            .formatter(v -> v + "t")
            .description("How long a hidden crystal stays hidden before its hitbox reappears if the server hasn't confirmed the kill. Default 4 is a safe match to server cadence; lower = safer against ghosts, higher = holds the spot clear longer.")
            .visibleWhen(() -> bool("hideOnHit")).group("Actions"));
        add(new BoolSetting("effectGate", "Effect-aware hit gate", true)
            .description("Skip the client-side hide when the swing wouldn't register (e.g. Weakness has cancelled the attack), so a nullified hit can't leave a ghost crystal.")
            .visibleWhen(() -> bool("hideOnHit")).group("Actions"));

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

        add(new BoolSetting("onBlockChange", "Trigger on block change", false)
            .description("Act immediately (skip the delay) when a block near the target changes.").group("Triggers"));
        add(new BoolSetting("onExplodeSound", "Trigger on explode sound", false)
            .description("Act immediately when an explosion sound plays.").group("Triggers"));
        add(new BoolSetting("onEntityTeleport", "Trigger on target teleport", false)
            .description("Act immediately when an entity teleports.").group("Triggers"));
        add(new BoolSetting("dualDamage", "Dual-position damage check", false)
            .description("Require the crystal be effective at both the target's current and 2-tick predicted position.").group("Safety"));
        add(new DoubleSetting("wallsRange", "Walls range (through-wall)", 0.0, 0.0, 6.0, 0.5)
            .description("Act on a non-visible crystal/position only within this distance (0 = strict line-of-sight).").group("Targeting"));
        add(new BoolSetting("offhandCrystal", "Use offhand crystals", false)
            .description("Place from the offhand if it holds end crystals (no hotbar switch).").group("Switch"));

        ClientEntityEvents.ENTITY_LOAD.register(this::onCrystalSpawn);
    }

    @Override
    public void onSoundPacket(ClientboundSoundPacket packet) {
        if (isEnabled() && bool("onExplodeSound") && packet.getSound().value() == SoundEvents.GENERIC_EXPLODE.value()) {
            forceAct = true;
        }
    }

    @Override
    public boolean onPacketReceive(Packet<?> packet) {
        if (isEnabled()) {
            if (bool("onBlockChange")
                    && (packet instanceof ClientboundBlockUpdatePacket || packet instanceof ClientboundSectionBlocksUpdatePacket)) {
                forceAct = true;
            } else if (bool("onEntityTeleport") && packet instanceof ClientboundTeleportEntityPacket) {
                forceAct = true;
            }
        }
        return false;
    }

    @Override
    public void onDisable() {
        forceAct = false;
        BossPvpAddon.crystalActive = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && prevSlot >= 0 && bool("switchBack")) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        prevSlot = -1;
        legitAim = null;
        recentlyHit.clear();
        breakRate.clear();
        CrystalHideManager.clear();
        HeldSlotManager.clear(this);
    }

    public void tick(Minecraft mc) {
        LocalPlayer me = mc.player;
        Level level = mc.level;
        // Advance the resync windows every tick (even with a menu open) so hidden crystals reappear on time.
        if (level != null && CrystalHideManager.hiddenCount() > 0) {
            CrystalHideManager.tick(id -> {
                Entity e = level.getEntity(id);
                return e == null || !e.isAlive();
            });
        }
        if (me == null || level == null || mc.gameMode == null || mc.gui.screen() != null) return;

        if (!HeldSlotManager.holds(this) && prevSlot >= 0) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            prevSlot = -1;
            return;
        }

        LivingEntity target = nearestEnemy(mc, me);
        if (target == null) { legitAim = null; HeldSlotManager.release(this); restoreSlot(mc, me); BossPvpAddon.crystalActive = false; return; }

        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_AUTOCRYSTAL);
        if (!HeldSlotManager.holds(this)) return;

        long now = System.currentTimeMillis();
        // Expire the KillAura-suppression flag if we haven't actually placed/broken recently.
        if (BossPvpAddon.crystalActive && now - lastCrystalActMs > CRYSTAL_HOLD_MS) BossPvpAddon.crystalActive = false;
        explosionSource = level.damageSources().explosion(null, null);
        pruneRecentlyHit(now);
        boolean force = forceAct;
        forceAct = false;

        int budget = integer("maxPerTick");
        boolean realRot = "Real".equals(choice("rotationMode"));

        if (realRot) easeLegit(me); else legitAim = null;

        if (bool("doBreak") && CrystalActions.gateOpen(force, now, lastBreakMs, PvpUtil.jitterMs(integer("breakDelay")))) {
            int done = 0;
            for (EndCrystal crystal : crystalsNear(mc, target.position(), decimal("breakRange"))) {
                if (done >= budget) break;
                if (bool("setDead") && recentlyHit.containsKey(crystal.getId())) continue;
                Vec3 cpos = crystal.position();
                double selfDmg = crystalDamage(me, cpos);
                double enemyDmg = crystalDamage(target, cpos);
                if (!safeToAct(me, selfDmg, enemyDmg, decimal("minBreakDamage"), false)) continue;
                if (bool("dualDamage") && predictedEnemyDamage(target, cpos) < decimal("minBreakDamage")) continue;
                if (!losOk(mc, me, cpos)) continue;
                if (!breakRate.tryAcquire(now, integer("maxBps"))) break;   // per-second cap reached this tick

                PvpUtil.ghostSafeAttack(mc, me, crystal, cpos, !"Packet".equals(choice("breakMode")));
                if (bool("setDead")) recentlyHit.put(crystal.getId(), now);
                maybeHideCrystal(me, crystal);
                if (realRot) legitAim = cpos;
                lastBreakMs = now;
                done++;
            }
            // Place-after-break: a successful break no longer forfeits the place phase this tick (improvement
            // #1) — fall through so a freed spot (incl. a just-hidden crystal's) is re-placed in the same tick.
            if (done > 0) { lastCrystalActMs = now; BossPvpAddon.crystalActive = true; }
        }

        if (bool("doPlace") && CrystalActions.gateOpen(force, now, lastPlaceMs, PvpUtil.jitterMs(integer("placeDelay")))) {
            List<Candidate> bases = bestBases(mc, me, target, integer("maxPlace"));
            if (bases.isEmpty()) return;
            boolean useOffhand = bool("offhandCrystal") && me.getOffhandItem().is(Items.END_CRYSTAL);
            if (!useOffhand && !ensureCrystal(mc, me)) return;
            int placed = 0;
            for (Candidate c : bases) {
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(c.base()).add(0, 0.5, 0), Direction.UP, c.base(), false);

                if (useOffhand) placeOffhand(mc, me, hit);
                else PvpUtil.ghostSafeUseOn(mc, me, hit);
                if (realRot) legitAim = hit.getLocation();
                placed++;
            }
            if (placed > 0) {
                lastPlaceMs = now;
                lastCrystalActMs = now;
                BossPvpAddon.crystalActive = true;
                if (bool("placeAlert")) AutismClientMessaging.sendPrefixed("§d[AutoCrystal] placed x" + placed);
            }
        }
    }

    private List<Candidate> bestBases(Minecraft mc, LocalPlayer me, LivingEntity target, int n) {
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
                    if (!facePlace && bool("dualDamage") && predictedEnemyDamage(target, crystalPos) < minEnemy) continue;
                    if (!losOk(mc, me, crystalPos)) continue;

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

    // Vanilla EndCrystalItem.useOn rejects a placement if ANY entity occupies the 1x2x1 space above the base
    // (not just living entities — a live crystal, item, or player's upper body all count). The old check used
    // a 1x1x1 box against LivingEntity only, so it never saw crystals and we placed into occupied spots. This
    // also makes CrystalHide pay off: a hidden crystal is treated as absent, so its spot re-places immediately.
    private boolean crystalBoxObstructed(Level level, BlockPos base) {
        AABB box = CrystalActions.placementBox(base);
        List<Entity> hits = level.getEntitiesOfClass(Entity.class, box, e ->
            CrystalActions.blocks(e.isRemoved(), e instanceof EndCrystal,
                e instanceof EndCrystal ec && CrystalHideManager.isHidden(ec.getId())));
        return !hits.isEmpty();
    }

    private Vec3 predicted(LivingEntity target) {

        if (bool("physicsPredict") && target instanceof Player pl) return PlayerSimulation.predictPosition(pl, 2);
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

    private LivingEntity nearestEnemy(Minecraft mc, LocalPlayer me) {
        java.util.Set<String> ids = PvpUtil.entityIds(list("entities"));
        double range = decimal("range");
        double bestDist = range * range;
        LivingEntity best = null;
        AABB area = me.getBoundingBox().inflate(range);
        for (LivingEntity e : mc.level.getEntitiesOfClass(LivingEntity.class, area, LivingEntity::isAlive)) {
            if (e == me || e instanceof LocalPlayer) continue;
            if (!PvpUtil.matchesEntity(e, ids)) continue;
            if (e instanceof Player pl) {
                if (pl.isSpectator()) continue;
                if (pl.getName().getString().equals(me.getName().getString())) continue;
                if (PvpUtil.isFriend(pl, BossPvpAddon.friends()) || (bool("teamCheck") && PvpUtil.isTeammate(me, pl))) continue;
            }
            double d = e.distanceToSqr(me);
            if (d < bestDist) { bestDist = d; best = e; }
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

        if (recentlyHit.containsKey(crystal.getId())) return;

        Vec3 cpos = crystal.position();
        double breakRange = decimal("breakRange");
        if (cpos.distanceToSqr(me.getEyePosition()) > breakRange * breakRange) return;
        if (!losOk(mc, me, cpos)) return;

        LivingEntity target = nearestEnemy(mc, me);
        if (target == null) return;

        explosionSource = mc.level.damageSources().explosion(null, null);
        double selfDmg = crystalDamage(me, cpos);
        double enemyDmg = crystalDamage(target, cpos);
        if (bool("antiSuicide") && DamageUtil.wouldSelfKill(selfDmg, me.getHealth(), me.getAbsorptionAmount())) return;
        if (selfDmg > decimal("maxSelfDamage")) return;
        if (enemyDmg < decimal("minBreakDamage")) return;

        long now = System.currentTimeMillis();
        if (!breakRate.tryAcquire(now, integer("maxBps"))) return;   // shared per-second cap with the tick loop

        PvpUtil.ghostSafeAttack(mc, me, crystal, cpos, !"Packet".equals(choice("breakMode")));
        if (bool("setDead")) recentlyHit.put(crystal.getId(), now);
        maybeHideCrystal(me, crystal);
    }

    // HCsCR-style client-side removal: after a break, drop the crystal's hitbox for a resync window so the
    // spot frees for the next raytrace/placement before the server's removal round-trips. The effect gate
    // skips the hide when the swing wouldn't register (Weakness cancelled it), so a nullified hit can't
    // leave a ghost. The resync window (CrystalHideManager.tick) reappears it if the server never confirms.
    private void maybeHideCrystal(LocalPlayer me, EndCrystal crystal) {
        if (!bool("hideOnHit")) return;
        if (bool("effectGate") && !breakWillRegister(me)) return;
        CrystalHideManager.hide(crystal.getId(), integer("resyncTicks"));
    }

    // Recompute the melee attack damage with active effects and check it still lands. Vanilla folds
    // Weakness/Strength into ATTACK_DAMAGE (and clamps at 0); we strip the effect modifiers back out and
    // re-apply them explicitly so the gate is auditable and survives the attribute's clamp.
    private boolean breakWillRegister(LocalPlayer me) {
        int weak = me.hasEffect(MobEffects.WEAKNESS) ? me.getEffect(MobEffects.WEAKNESS).getAmplifier() + 1 : 0;
        int str = me.hasEffect(MobEffects.STRENGTH) ? me.getEffect(MobEffects.STRENGTH).getAmplifier() + 1 : 0;
        double weaponBase = me.getAttributeValue(Attributes.ATTACK_DAMAGE) - 3.0 * str + 4.0 * weak;
        return DamageUtil.meleeRegistersHit(DamageUtil.effectiveMeleeDamage(weaponBase, weak, str));
    }

    private void pruneRecentlyHit(long now) {
        if (recentlyHit.isEmpty()) return;
        long window = Math.max(300L, integer("breakDelay") * 3L);
        recentlyHit.values().removeIf(t -> now - t > window);
    }

    // Line-of-sight gate: visible always passes; a non-visible point passes only within wallsRange (0 = off).
    private boolean losOk(Minecraft mc, LocalPlayer me, Vec3 to) {
        if (!bool("raytrace")) return true;
        Vec3 eyes = me.getEyePosition();
        if (PvpUtil.canSee(mc, me, eyes, to)) return true;
        double wr = decimal("wallsRange");
        return wr > 0.0 && to.distanceToSqr(eyes) <= wr * wr;
    }

    // Approx damage to the target at its 2-tick predicted position (optimistic full exposure) for dual-damage.
    private double predictedEnemyDamage(LivingEntity target, Vec3 crystalPos) {
        Vec3 pp = (target instanceof Player pl)
            ? PlayerSimulation.predictPosition(pl, 2)
            : target.position().add(target.getDeltaMovement().scale(2.0));
        if (pp == null) return 0.0;
        double raw = DamageUtil.rawExplosionDamage(1.0, pp.distanceTo(crystalPos), DamageUtil.CRYSTAL_POWER);
        if (raw <= 0.0) return 0.0;
        return DamageUtil.effectiveDamage(target, raw, explosionSource);
    }

    // Silent place from the offhand (mirrors ghostSafeUseOn but with the off-hand).
    private void placeOffhand(Minecraft mc, LocalPlayer me, BlockHitResult hit) {
        AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(me);
        AutismRotationUtil.Rotation look = AutismRotationUtil.normalizeToSensitivity(
            AutismRotationUtil.lookingAt(hit.getLocation(), me.getEyePosition()), cur);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundMovePlayerPacket.Rot(
                look.yaw(), look.pitch(), me.onGround(), me.horizontalCollision));
        }
        mc.gameMode.useItemOn(me, InteractionHand.OFF_HAND, hit);
        me.swing(InteractionHand.OFF_HAND);
    }
}
