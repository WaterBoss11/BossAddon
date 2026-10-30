package com.boss.pvp.module.combat;

import autismclient.api.AutismAddons;
import autismclient.api.module.*;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.module.movement.CoreMovementSupport;
import com.boss.pvp.util.CombatManager;
import com.boss.pvp.util.InputUtil;
import com.boss.pvp.util.InventoryUtil;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.NativeAimBridge;
import com.boss.pvp.util.pvp.PvpUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/** Real-fall mace automation. Aiming executes the installed host's own AimAssist implementation. */
public final class AutoMaceModule extends Module implements HeldSlotManager.Revocable {
    private enum Step { IDLE, AXE, SHIELD_WAIT, SPEAR, MACE }
    private static final int SLOT_PRIORITY = HeldSlotManager.PRIORITY_AUTOWEAPON;
    private Step step = Step.IDLE;
    private LocalPlayer player;
    private Level level;
    private LivingEntity target;
    private int previousSlot = -1;
    private int selectedSlot = -1;
    private int maceSlot = -1;
    private int selectedAt;
    private ItemStack selectedStack = ItemStack.EMPTY;
    private long startedAt;
    private long lastHit;
    private long lastLunge;
    private long selectedNanos;
    private boolean dispatching;
    private boolean accepted;
    private boolean smashedThisFall;
    private boolean spearJabbed;
    private boolean cancelledUntilGround;
    private String status = "Idle";

    public AutoMaceModule() {
        super(BossPvpAddon.ID + ":automace", "AutoMace", "Real-fall mace hits with native AUTISM aiming, optional axe stun and spear reach preparation.");
        add(new BoolSetting("aim", "Aim with native AimAssist", true).group("Aiming"));
        add(new ChoiceSetting("aimSettings", "Aim configuration", "AutoMace", "AutoMace", "Host AimAssist").group("Aiming"));
        add(new ChoiceSetting("smooth", "Native smoothing", "Interpolation", "Human", "Interpolation", "Instant")
            .description("Uses the host's actual smoother. Human requires API v4; API v3 uses its native interpolation.").group("Aiming"));
        add(new IntSetting("horizontalSpeed", "Horizontal speed %", 60, 1, 100, 1).group("Aiming"));
        add(new IntSetting("verticalSpeed", "Vertical speed %", 60, 1, 100, 1).group("Aiming"));
        add(new IntSetting("directionFactor", "Direction factor %", 100, 0, 100, 1).group("Aiming"));
        add(new DoubleSetting("midpoint", "Native midpoint", 0.35, 0.0, 1.0, 0.05).group("Aiming"));
        add(new ChoiceSetting("point", "Target point", "Body", "Nearest", "Center", "Head", "Body", "Feet").group("Aiming"));
        add(new ChoiceSetting("axis", "Aim axes", "Both", "Both", "Horizontal", "Vertical").group("Aiming"));
        add(new IntSetting("safeZone", "Safe zone %", 3, 0, 100, 1).group("Aiming"));
        add(RegistryListSetting.entityTypes("entities", "Targets", "minecraft:player").group("Targeting"));
        add(new DoubleSetting("range", "Target search range", 4.5, 1.0, 8.0, 0.1)
            .description("Search only. Each actual hit still uses the held weapon's native range.").group("Targeting"));
        add(new IntSetting("fov", "FOV", 100, 0, 180, 1).group("Targeting"));
        add(new ChoiceSetting("priority", "Priority", "Direction", "Direction", "Health", "Distance", "HurtTime", "Age", "Type").group("Targeting"));
        add(new IntSetting("hurtTime", "Maximum target hurt time", 10, 0, 10, 1).group("Targeting"));
        add(new BoolSetting("requireLos", "Require line of sight", true).group("Targeting"));
        add(new BoolSetting("attackKey", "Require held attack key", false).group("Activation"));
        add(new KeybindSetting("activationKey", "Optional held activation bind", -1).group("Activation"));
        add(new BoolSetting("prepareOnAscent", "Prepare while rising", true).group("Activation"));
        add(new DoubleSetting("minFall", "Minimum fall distance", 1.5, 1.5, 64.0, 0.1).group("Smash"));
        add(new DoubleSetting("maxFall", "Maximum fall distance", 64.0, 1.5, 256.0, 0.5).group("Smash"));
        add(new DoubleSetting("minDownSpeed", "Minimum downward speed", 0.05, 0.0, 3.0, 0.01).group("Smash"));
        add(new BoolSetting("oncePerFall", "One smash per fall", true).group("Smash"));
        add(new DoubleSetting("maceCharge", "Minimum mace charge", 0.0, 0.0, 1.0, 0.05)
            .description("Vanilla smash bonus is separate from ordinary base-damage charge. Increase to require a charged hit.").group("Smash"));
        add(new IntSetting("hitDelay", "Minimum hit interval (ms)", 250, 0, 2000, 25).group("Smash"));
        add(new DoubleSetting("minHealth", "Minimum own health", 6.0, 0.0, 40.0, 0.5).group("Safety"));
        add(new IntSetting("timeout", "Sequence timeout (ms)", 1500, 100, 5000, 50).group("Safety"));
        add(new BoolSetting("switch", "Select mace automatically", true).group("Swapping"));
        add(new BoolSetting("restoreSlot", "Restore previous slot", true).group("Swapping"));
        add(new IntSetting("maceSlot", "Mace slot (-1 = best)", -1, -1, 8, 1).group("Swapping"));
        add(new ChoiceSetting("macePreference", "Mace preference", "Best damage", "Best damage", "Density", "Breach", "Wind Burst", "First").group("Swapping"));
        add(new BoolSetting("axeStun", "Axe stun slam", false).group("Axe / Attributes"));
        add(new BoolSetting("axeAttributes", "Axe attribute swap (experimental)", false)
            .description("Equips an axe first, then swaps to the mace for a real falling hit. Stale-attribute behavior depends on the server.").group("Axe / Attributes"));
        add(new IntSetting("axeSlot", "Axe slot (-1 = auto)", -1, -1, 8, 1).group("Axe / Attributes"));
        add(new IntSetting("axeHold", "Axe hold ticks", 1, 0, 20, 1).group("Axe / Attributes"));
        add(new DoubleSetting("axeCharge", "Minimum axe charge", 0.9, 0.0, 1.0, 0.05).group("Axe / Attributes"));
        add(new BoolSetting("confirmStun", "Wait for shield to lower", true).group("Axe / Attributes"));
        add(new IntSetting("stunDelay", "Stun follow-up ticks", 1, 0, 10, 1).group("Axe / Attributes"));
        add(new BoolSetting("spearReach", "Spear reach swap", false)
            .description("Uses a real spear jab at its own reach, then swaps to mace when mace range is legal. Does not copy spear range onto a mace.").group("Spear"));
        add(new BoolSetting("spearJab", "Jab before mace", true).group("Spear"));
        add(new IntSetting("spearSlot", "Spear slot (-1 = auto)", -1, -1, 8, 1).group("Spear"));
        add(new IntSetting("spearHold", "Spear hold ticks", 1, 0, 20, 1).group("Spear"));
        add(new BoolSetting("autoLunge", "Spear swap for auto lunge", false)
            .description("Selects a Lunge-enchanted spear and makes a native jab to trigger its server-side dash. No fake velocity.").group("Auto Lunge"));
        add(new BoolSetting("lungeOnGround", "Allow ground lunges", false).group("Auto Lunge"));
        add(new BoolSetting("lungeApproachOnly", "Only lunge outside mace range", true).group("Auto Lunge"));
        add(new DoubleSetting("lungeRange", "Lunge target range", 6.0, 2.0, 8.0, 0.1).group("Auto Lunge"));
        add(new IntSetting("lungeDelay", "Minimum lunge interval (ms)", 1000, 250, 5000, 50).group("Auto Lunge"));
        add(new IntSetting("lungeFood", "Minimum hunger", 7, 7, 20, 1)
            .description("Vanilla Lunge requires hunger above 6, unless creative. Exhaustion and durability are consumed normally.").group("Auto Lunge"));
        add(new DoubleSetting("lungeClearance", "Clear forward path", 1.5, 0.5, 4.0, 0.1).group("Auto Lunge"));
    }

    @Override public String info() { return status; }
    @Override public void onEnable() { cancelledUntilGround = false; }
    @Override public void onDisable() { reset(Minecraft.getInstance(), "Idle"); }
    @Override public void onGameLeft() { onDisable(); smashedThisFall = cancelledUntilGround = false; lastHit = lastLunge = 0L; }
    @Override public void onGameJoin() { onGameLeft(); }
    @Override public void onLeaseRevoked() { reset(Minecraft.getInstance(), "Yielded to item use"); }

    public boolean ownsAttack(Entity entity) {
        return dispatching && entity == target && HeldSlotManager.holds(this);
    }

    public boolean blocksOtherAttack() {
        return step != Step.IDLE && HeldSlotManager.holds(this) && !dispatching;
    }

    public void attackAllowed(Entity entity) {
        if (ownsAttack(entity)) accepted = true;
    }

    public void tick(Minecraft mc) {
        if (!ready(mc)) { reset(mc, "Paused"); return; }
        if (mc.player.onGround()) cancelledUntilGround = false;
        if (mc.player.onGround() || mc.player.getDeltaMovement().y > 0.0) smashedThisFall = false;
        if (cancelledUntilGround) { status = "Cancelled until landing"; return; }
        boolean groundLunge = mc.player.onGround() && bool("autoLunge") && bool("lungeOnGround");
        if (mc.player.onGround() && !groundLunge || !bool("prepareOnAscent") && mc.player.getDeltaMovement().y >= 0.0 && !groundLunge
                || bool("oncePerFall") && smashedThisFall) { reset(mc, "Waiting for fall"); return; }
        if (step != Step.IDLE) {
            if (mc.player != player || mc.level != level || !validTarget(mc, target) || !ownsSelection(mc)
                    || !Decisions.withinDeadline(System.nanoTime() - startedAt, integer("timeout"))) {
                reset(mc, "Sequence cancelled"); return;
            }
            HeldSlotManager.request(this, SLOT_PRIORITY);
        }
        if (bool("aim") && !NativeAimBridge.tick(aimOptions())) { reset(mc, "Native AimAssist unavailable"); return; }
        if (step == Step.IDLE) {
            if (lastHit != 0L && System.nanoTime() - lastHit < integer("hitDelay") * 1_000_000L) return;
            LivingEntity candidate = findTarget(mc);
            if (candidate == null) { status = "Aiming"; return; }
            int mace = findMace(mc, candidate);
            if (mace < 0) { status = "No configured mace"; return; }
            int axe = findSupport(mc, false);
            int spear = findSupport(mc, true);
            if (mc.player.onGround() && (spear < 0
                    || !Decisions.lungeEligible(lungeLevel(mc.player.getInventory().getItem(spear)), mc.player.getFoodData().getFoodLevel(),
                        mc.player.getAbilities().instabuild, integer("lungeFood"), true, bool("lungeOnGround"))
                    || lastLunge != 0L && System.nanoTime() - lastLunge < integer("lungeDelay") * 1_000_000L
                    || bool("lungeApproachOnly") && mc.player.isWithinAttackRange(mc.player.getInventory().getItem(mace), candidate.getBoundingBox(), 0.0))) {
                status = "Waiting for eligible Lunge"; return;
            }
            if (!HeldSlotManager.tryAcquire(this, SLOT_PRIORITY)) { status = "Waiting for item ownership"; return; }
            player = mc.player; level = mc.level; target = candidate;
            previousSlot = player.getInventory().getSelectedSlot(); maceSlot = mace;
            startedAt = System.nanoTime();
            if (bool("autoLunge") && spear >= 0 && wantsLunge(mc)) {
                step = Step.SPEAR;
                if (!select(mc, spear)) { reset(mc, "Lunge spear selection refused"); return; }
            } else if (axe >= 0 && (bool("axeAttributes") || bool("axeStun") && candidate.isBlocking())) {
                step = Step.AXE;
                if (!select(mc, axe)) { reset(mc, "Axe selection refused"); return; }
            } else if (bool("spearReach") && spear >= 0 && !inRange(mc, player.getInventory().getItem(mace))) {
                step = Step.SPEAR;
                if (!select(mc, spear)) { reset(mc, "Spear selection refused"); return; }
            } else {
                step = Step.MACE;
                if (!select(mc, mace)) { reset(mc, "Mace selection refused"); return; }
            }
        }
        switch (step) {
            case AXE -> {
                status = "Axe preparation";
                if (player.tickCount - selectedAt < integer("axeHold") || !speedSettled()) return;
                if (bool("axeStun") && target.isBlocking()) {
                    if (!smashReady(player) || player.getAttackStrengthScale(0.0f) < decimal("axeCharge")
                            || !inRange(mc, player.getMainHandItem()) || !aligned(mc, target, player.getMainHandItem())) return;
                    if (!attack(mc)) { reset(mc, "Axe attack refused"); return; }
                    step = Step.SHIELD_WAIT; selectedAt = player.tickCount;
                    return;
                }
                if (bool("axeAttributes")) {
                    var plannedMace = player.getInventory().getItem(maceSlot);
                    if (!plannedMace.is(Items.MACE)) { reset(mc, "Configured mace changed"); return; }
                    if (!Decisions.attributeSwapReady(smashReady(player), inRange(mc, plannedMace), aligned(mc, target, plannedMace))) return;
                }
                step = Step.MACE;
                if (!select(mc, maceSlot)) { reset(mc, "Mace selection refused"); return; }
                hitMace(mc);
            }
            case SHIELD_WAIT -> {
                status = "Waiting for shield";
                if (player.tickCount - selectedAt < integer("stunDelay") || bool("confirmStun") && target.isBlocking()) return;
                step = Step.MACE;
                if (!select(mc, maceSlot)) { reset(mc, "Mace selection refused"); return; }
                hitMace(mc);
            }
            case SPEAR -> {
                if (spearJabbed && player.onGround() && bool("autoLunge")) {
                    reset(mc, "Native Lunge requested", true); return;
                }
                boolean lunge = !spearJabbed && bool("autoLunge") && wantsLunge(mc);
                status = lunge ? "Preparing native Lunge" : "Spear reach preparation";
                if (inRange(mc, player.getInventory().getItem(maceSlot)) && !lunge) {
                    step = Step.MACE;
                    if (!select(mc, maceSlot)) { reset(mc, "Mace selection refused"); return; }
                    hitMace(mc);
                    return;
                }
                if (spearJabbed || !lunge && !bool("spearJab") || player.tickCount - selectedAt < integer("spearHold") || !speedSettled()
                        || player.getAttackStrengthScale(0.0f) < 1.0f || player.getCooldowns().isOnCooldown(player.getMainHandItem())
                        || spearWouldHitFriend(mc)) return;
                if (lunge) {
                    if (!lungeReady(mc) || !lungeAligned(mc)) return;
                } else {
                    if (lungeLevel(player.getMainHandItem()) > 0) { status = "Lunge disabled or cooling down"; return; }
                    if (!aligned(mc, target, player.getMainHandItem()) || !inRange(mc, player.getMainHandItem())) return;
                }
                var piercing = player.getMainHandItem().get(DataComponents.PIERCING_WEAPON);
                if (piercing == null) { reset(mc, "Selected item is not a spear"); return; }
                player.connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision));
                mc.gameMode.piercingAttack(piercing);
                if (inRange(mc, player.getMainHandItem())) CombatManager.recordAttack(target);
                if (lunge) { lastLunge = System.nanoTime(); status = "Native Lunge requested"; }
                spearJabbed = true;
            }
            case MACE -> { status = "Waiting for smash"; hitMace(mc); }
            default -> { }
        }
    }

    private double searchRange() {
        return bool("autoLunge") ? Math.max(decimal("range"), decimal("lungeRange")) : decimal("range");
    }

    private boolean wantsLunge(Minecraft mc) {
        long elapsed = lastLunge == 0L ? Long.MAX_VALUE : System.nanoTime() - lastLunge;
        return target != null && target.distanceToSqr(mc.player) <= decimal("lungeRange") * decimal("lungeRange")
            && elapsed >= integer("lungeDelay") * 1_000_000L
            && (!bool("lungeApproachOnly") || !inRange(mc, mc.player.getInventory().getItem(maceSlot)));
    }

    private boolean lungeReady(Minecraft mc) {
        if (!Decisions.lungeEligible(lungeLevel(player.getMainHandItem()), player.getFoodData().getFoodLevel(),
                player.getAbilities().instabuild, integer("lungeFood"), player.onGround(), bool("lungeOnGround"))) return false;
        double delay = player.getCurrentItemAttackStrengthDelay()
            * Math.max(1.0f, player.getMainHandItem().getOrDefault(DataComponents.MINIMUM_ATTACK_CHARGE, 0.0f));
        var info = mc.getConnection().getPlayerInfo(player.getUUID());
        long wait = AutoWeaponModule.Decisions.settleNanos(delay, mc.level.tickRateManager().nanosecondsPerTick(),
            info == null ? 0 : info.getLatency(), 50);
        if (selectedNanos != 0L && System.nanoTime() - selectedNanos < wait) return false;
        Vec3 forward = new Vec3(player.getLookAngle().x, 0.0, player.getLookAngle().z).normalize();
        return forward.lengthSqr() > 0.0 && CoreMovementSupport.clearPath(mc, player, player.getBoundingBox(),
            forward.scale(decimal("lungeClearance")));
    }

    private boolean lungeAligned(Minecraft mc) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(searchRange()));
        var hit = target.getBoundingBox().clip(eye, end);
        return hit.isPresent() && PvpUtil.canSee(mc, player, eye, hit.get());
    }

    private static int lungeLevel(ItemStack stack) {
        for (var enchantment : stack.getEnchantments().entrySet()) {
            if (enchantment.getKey().is(Enchantments.LUNGE)) return enchantment.getIntValue();
        }
        return 0;
    }

    private void hitMace(Minecraft mc) {
        if (!smashReady(player) || !player.getMainHandItem().is(Items.MACE)
                || player.getAttackStrengthScale(0.0f) < decimal("maceCharge")
                || !inRange(mc, player.getMainHandItem()) || !aligned(mc, target, player.getMainHandItem())) return;
        if (attack(mc)) {
            smashedThisFall = true;
            lastHit = System.nanoTime();
            reset(mc, "Smash sent", true);
        } else reset(mc, "Mace attack refused");
    }

    private boolean attack(Minecraft mc) {
        if (!validTarget(mc, target) || !ownsSelection(mc)) return false;
        dispatching = true; accepted = false;
        try {
            player.connection.send(new ServerboundMovePlayerPacket.Rot(player.getYRot(), player.getXRot(), player.onGround(), player.horizontalCollision));
            mc.gameMode.attack(player, target);
            if (accepted) player.swing(InteractionHand.MAIN_HAND);
            return accepted;
        } finally { dispatching = false; }
    }

    private boolean ready(Minecraft mc) {
        return isEnabled() && mc != null && mc.player != null && mc.level != null && mc.gameMode != null
            && mc.getConnection() != null && mc.gui.screen() == null && !mc.isPaused() && mc.isWindowActive()
            && mc.player.isAlive() && !mc.player.isSpectator() && !mc.player.isUsingItem()
            && !mc.player.isFallFlying() && !mc.player.getAbilities().flying && !mc.player.isInLiquid()
            && !mc.player.onClimbable() && !mc.player.isPassenger() && !CombatManager.isCombatPaused()
            && mc.player.getHealth() + mc.player.getAbsorptionAmount() >= decimal("minHealth")
            && !PvpUtil.hostEnabled("hide") && (!bool("attackKey") || mc.options.keyAttack.isDown())
            && (integer("activationKey") == -1 || InputUtil.isBindPressed(mc, integer("activationKey")));
    }

    private LivingEntity findTarget(Minecraft mc) {
        var ids = PvpUtil.entityIds(list("entities"));
        LivingEntity best = null;
        double score = Double.POSITIVE_INFINITY;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || !PvpUtil.matchesEntity(living, ids) || !validTarget(mc, living)) continue;
            double next = switch (choice("priority")) {
                case "Health" -> living.getHealth() + living.getAbsorptionAmount();
                case "HurtTime" -> living.hurtTime;
                case "Age" -> -living.tickCount;
                case "Distance" -> living.distanceToSqr(mc.player);
                default -> PvpUtil.angleTo(mc.player, living);
            };
            if (next < score) { score = next; best = living; }
        }
        return best;
    }

    private boolean validTarget(Minecraft mc, LivingEntity entity) {
        return entity != null && mc.player != null && entity != mc.player && entity.level() == mc.level
            && entity.isAlive() && !entity.isRemoved() && !entity.isSpectator() && entity.hurtTime <= integer("hurtTime")
            && PvpUtil.matchesEntity(entity, PvpUtil.entityIds(list("entities")))
            && (!(entity instanceof net.minecraft.world.entity.player.Player other) || !PvpUtil.isProtectedPlayer(mc, other))
            && entity.distanceToSqr(mc.player) <= searchRange() * searchRange()
            && PvpUtil.angleTo(mc.player, entity) <= integer("fov")
            && (!bool("requireLos") || PvpUtil.canSeeEntity(mc, mc.player, entity));
    }

    private boolean smashReady(LocalPlayer local) {
        return MaceItem.canSmashAttack(local) && Decisions.fallWindow(local.fallDistance, local.getDeltaMovement().y,
            decimal("minFall"), decimal("maxFall"), decimal("minDownSpeed"));
    }

    private boolean inRange(Minecraft mc, ItemStack stack) {
        return mc.player.isWithinAttackRange(stack, target.getBoundingBox(), 0.0);
    }

    private boolean aligned(Minecraft mc, LivingEntity entity, ItemStack stack) {
        Vec3 eye = mc.player.getEyePosition();
        double reach = mc.player.getAttackRangeWith(stack).effectiveMaxRange(mc.player);
        var hit = entity.getBoundingBox().clip(eye, eye.add(mc.player.getLookAngle().scale(reach)));
        if (hit.isEmpty() || !PvpUtil.canSee(mc, mc.player, eye, hit.get())) return false;
        double distance = eye.distanceToSqr(hit.get());
        for (var other : mc.level.getEntities(mc.player, mc.player.getBoundingBox().expandTowards(mc.player.getLookAngle().scale(reach)).inflate(1.0))) {
            if (other == entity || !other.isPickable() || other.isSpectator()) continue;
            var obstruction = other.getBoundingBox().clip(eye, eye.add(mc.player.getLookAngle().scale(reach)));
            if (obstruction.isPresent() && eye.distanceToSqr(obstruction.get()) < distance) return false;
        }
        return true;
    }

    private boolean spearWouldHitFriend(Minecraft mc) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(player.getAttackRangeWith(player.getMainHandItem()).effectiveMaxRange(player)));
        var ids = PvpUtil.entityIds(list("entities"));
        for (var other : mc.level.getEntities(player, player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0))) {
            if (!(other instanceof LivingEntity living) || other == player) continue;
            boolean protectedTarget = !PvpUtil.matchesEntity(living, ids)
                || other instanceof net.minecraft.world.entity.player.Player possibleFriend && PvpUtil.isProtectedPlayer(mc, possibleFriend);
            if (protectedTarget && other.getBoundingBox().clip(eye, end).isPresent()) return true;
        }
        return false;
    }

    private int findMace(Minecraft mc, Entity entity) {
        int configured = integer("maceSlot");
        if (!bool("switch")) return mc.player.getMainHandItem().is(Items.MACE) ? mc.player.getInventory().getSelectedSlot() : -1;
        if (configured >= 0) return mc.player.getInventory().getItem(configured).is(Items.MACE) ? configured : -1;
        int best = -1;
        int bestPreference = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < 9; slot++) {
            var stack = mc.player.getInventory().getItem(slot);
            if (!stack.is(Items.MACE)) continue;
            double score = BossPvpAddon.autoWeapon.scoreWeapon(stack, entity, mc.player);
            if ("First".equals(choice("macePreference"))) return slot;
            int preference = 0;
            for (var enchantment : stack.getEnchantments().entrySet()) {
                if ("Density".equals(choice("macePreference")) && enchantment.getKey().is(Enchantments.DENSITY)
                        || "Breach".equals(choice("macePreference")) && enchantment.getKey().is(Enchantments.BREACH)
                        || "Wind Burst".equals(choice("macePreference")) && enchantment.getKey().is(Enchantments.WIND_BURST)) preference = Math.max(preference, enchantment.getIntValue());
            }
            if (Decisions.betterMace(preference, bestPreference, score, bestScore)) {
                best = slot; bestPreference = preference; bestScore = score;
            }
        }
        return best;
    }

    private int findSupport(Minecraft mc, boolean spear) {
        int configured = integer(spear ? "spearSlot" : "axeSlot");
        int best = -1;
        double bestRank = Double.NEGATIVE_INFINITY;
        for (int slot = 0; slot < 9; slot++) {
            if (configured >= 0 && slot != configured) continue;
            var stack = mc.player.getInventory().getItem(slot);
            var weapon = stack.get(DataComponents.WEAPON);
            boolean eligible = spear ? stack.has(DataComponents.PIERCING_WEAPON) && stack.has(DataComponents.KINETIC_WEAPON)
                && (!bool("autoLunge") || lungeLevel(stack) > 0)
                : weapon != null && weapon.disableBlockingForSeconds() > 0.0f;
            if (!eligible) continue;
            double rank = spear ? (bool("autoLunge") ? lungeLevel(stack) : mc.player.getAttackRangeWith(stack).effectiveMaxRange(mc.player))
                : AutoWeaponModule.weaponAttribute(stack, mc.player, Attributes.ATTACK_DAMAGE);
            if (rank > bestRank || rank == bestRank && slot == mc.player.getInventory().getSelectedSlot()) {
                best = slot; bestRank = rank;
            }
        }
        return best;
    }

    private boolean select(Minecraft mc, int slot) {
        if (!InventoryUtil.validHotbarSlot(slot) || !HeldSlotManager.holds(this)
                || step == Step.MACE && !player.getInventory().getItem(slot).is(Items.MACE)) return false;
        boolean chargedHeld = player.getInventory().getSelectedSlot() == slot && speedSettled()
            && player.getAttackStrengthScale(0.0f) >= 1.0f;
        InventoryUtil.selectHotbarSlot(mc, slot);
        if (player.getInventory().getSelectedSlot() != slot) return false;
        selectedSlot = slot; selectedStack = player.getMainHandItem().copy(); selectedAt = player.tickCount;
        selectedNanos = chargedHeld ? 0L : System.nanoTime();
        return true;
    }

    private boolean speedSettled() {
        return AutoWeaponModule.Decisions.attributeMatches(player.getAttributeValue(Attributes.ATTACK_SPEED),
            AutoWeaponModule.weaponAttribute(player.getMainHandItem(), player, Attributes.ATTACK_SPEED));
    }

    private boolean ownsSelection(Minecraft mc) {
        if (mc == null || mc.player != player || mc.level != level || player == null || !HeldSlotManager.holds(this)
                || player.getInventory().getSelectedSlot() != selectedSlot) return false;
        var expected = selectedStack.copy();
        if (expected.isDamageableItem()) expected.setDamageValue(player.getMainHandItem().getDamageValue());
        return ItemStack.isSameItemSameComponents(expected, player.getMainHandItem());
    }

    private Map<String, String> aimOptions() {
        if ("Host AimAssist".equals(choice("aimSettings"))) return Map.of();
        var options = new HashMap<String, String>();
        options.put("entities", String.join("|", list("entities")));
        options.put("range", Double.toString(searchRange())); options.put("fov", value("fov"));
        options.put("priority", value("priority")); options.put("target-point", value("point"));
        options.put("axis", value("axis")); options.put("safe-zone", value("safeZone"));
        options.put("hurt-time", value("hurtTime"));
        options.put("smooth-mode", "Human".equals(choice("smooth")) && AutismAddons.apiVersion() < 4 ? "Interpolation" : choice("smooth"));
        options.put("horizontal-speed", value("horizontalSpeed")); options.put("vertical-speed", value("verticalSpeed"));
        options.put("direction-factor", value("directionFactor")); options.put("midpoint", value("midpoint"));
        return options;
    }

    private void reset(Minecraft mc, String reason) {
        reset(mc, reason, false);
    }

    private void reset(Minecraft mc, String reason, boolean completed) {
        if (!completed && step != Step.IDLE && player != null && !player.onGround()) cancelledUntilGround = true;
        if (previousSlot >= 0 && bool("restoreSlot") && ownsSelection(mc)) InventoryUtil.selectHotbarSlot(mc, previousSlot);
        HeldSlotManager.clear(this);
        NativeAimBridge.reset();
        step = Step.IDLE; target = null; player = null; level = null;
        previousSlot = selectedSlot = maceSlot = -1; selectedStack = ItemStack.EMPTY;
        dispatching = accepted = spearJabbed = false; status = reason;
    }

    static final class Decisions {
        private Decisions() {}
        static boolean fallWindow(double fall, double velocity, double minimum, double maximum, double minDownSpeed) {
            return Double.isFinite(fall) && Double.isFinite(velocity) && Double.isFinite(minimum) && Double.isFinite(maximum)
                && Double.isFinite(minDownSpeed) && minimum >= 1.5 && maximum >= minimum && minDownSpeed >= 0.0
                && fall > 1.5 && fall >= minimum && fall <= maximum && velocity < -minDownSpeed;
        }
        static boolean attributeSwapReady(boolean realSmash, boolean legalMaceReach, boolean actuallyAimed) {
            return realSmash && legalMaceReach && actuallyAimed;
        }
        static boolean lungeEligible(int enchantmentLevel, int food, boolean creative, int minimumFood,
                                     boolean grounded, boolean allowGround) {
            return enchantmentLevel > 0 && minimumFood >= 7 && minimumFood <= 20
                && (creative || food >= minimumFood) && (!grounded || allowGround);
        }
        static boolean betterMace(int preference, int previousPreference, double damage, double previousDamage) {
            return Double.isFinite(damage) && (preference > previousPreference || preference == previousPreference && damage > previousDamage);
        }
        static boolean withinDeadline(long elapsed, int timeoutMillis) {
            return elapsed >= 0L && timeoutMillis > 0 && elapsed <= timeoutMillis * 1_000_000L;
        }
    }
}
