package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.VelocityMath;
import com.boss.pvp.util.MenuMode;

import autismclient.modules.Module;
import autismclient.api.module.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Velocity (a.k.a. Anti-Knockback v2) — ported from LiquidBounce's ModuleVelocity + its
 * mode family (Modify, Reversal, Strafe, JumpReset, Lag, and a handful of server/anticheat
 * presets: Hypixel, Dexland, Hylex, BlocksMC, AAC4.4.2, Intave).
 *
 * Boss's PVP has no packet-event bus like LiquidBounce, so instead of intercepting and
 * mutating the raw ClientboundSetEntityMotionPacket in place, {@link com.boss.pvp.client.mixin.VelocityMixin}
 * cancels the packet outright on the network thread and hands the raw (x, y, z, isFallDamage)
 * values to {@link #onMotionPacket}, which re-applies a modified velocity on the next client
 * tick (or later, for delay-based modes) via the same tick() polling convention every other
 * module in this addon already uses.
 *
 * NOTE: LiquidBounce's Grim2344/2371 modes rely on a very specific block-digging-packet
 * exploit sequence (see their source comments) that's fragile/server-version-specific — left
 * out of this first pass rather than porting a half-verified exploit. Everything else in
 * ModuleVelocity's mode list is ported below.
 */
public final class VelocityModule extends Module {

    public VelocityModule() {
        super(BossPvpAddon.ID + ":velocity", "Velocity", "Reduces or changes the knockback you take.");

        // Simple settings: the mode and how often it applies.
        add(new ChoiceSetting("mode", "Mode", "Modify",
            "Modify", "Reversal", "Strafe", "JumpReset", "Lag",
            "Hypixel", "Dexland", "Hylex", "BlocksMC", "AAC4.4.2", "Intave").group("General"));
        add(new IntSetting("chance", "Chance to apply", 100, 0, 100, 1).formatter(v -> v + "%").group("General"));

        // Modify — the default mode's main knobs (also Simple).
        add(new IntSetting("horizontal", "Keep horizontal knockback", 40, 0, 100, 1).formatter(v -> v + "%").group("Modify"));
        add(new IntSetting("vertical", "Keep vertical knockback", 100, 0, 100, 1).formatter(v -> v + "%").group("Modify"));

        // Advanced — per-mode tuning, hidden in Simple menu mode.
        add(new IntSetting("reversalDelay", "Reversal delay", 2, 1, 5, 1).formatter(v -> v + "t").visibleWhen(MenuMode::advanced).group("Reversal"));
        add(new IntSetting("reversalStrength", "Reversal strength", 50, 0, 100, 1).formatter(v -> v + "%").visibleWhen(MenuMode::advanced).group("Reversal"));

        add(new IntSetting("strafeDelay", "Strafe delay", 2, 0, 10, 1).formatter(v -> v + "t").visibleWhen(MenuMode::advanced).group("Strafe"));
        add(new IntSetting("strafeStrength", "Strafe strength", 100, 10, 200, 1).formatter(v -> v + "%").visibleWhen(MenuMode::advanced).group("Strafe"));

        add(new IntSetting("jumpResetDelay", "Ticks before jump", 2, 0, 20, 1).formatter(v -> v + "t").visibleWhen(MenuMode::advanced).group("JumpReset"));

        add(new IntSetting("lagTicks", "Delay before reapplying", 5, 1, 20, 1).formatter(v -> v + "t").visibleWhen(MenuMode::advanced).group("Lag"));
        add(new BoolSetting("lagJumpReset", "Jump on release", false).visibleWhen(MenuMode::advanced).group("Lag"));

        // AAC442 / Dexland / Intave share a "reduce factor".
        add(new IntSetting("reduceFactor", "Preset knockback reduction", 62, 0, 100, 1).formatter(v -> v + "%").visibleWhen(MenuMode::advanced).group("Presets"));
    }

    // --- state shared across modes ---
    private boolean pendingReversal = false;
    private int reversalTicks = 0;

    private boolean strafeArmed = false;
    private int strafeTicksLeft = -1;

    private boolean jumpArmed = false;
    private int jumpTicksLeft = -1;

    private boolean lagPending = false;
    private int lagTicksLeft = 0;
    private double lagX, lagY, lagZ;

    private boolean hypixelAbsorbed = false;

    private int dexlandCount = 0;
    private long dexlandLastAttackMs = 0L;

    private long intaveLastAttackMs = 0L;

    @Override
    public void onDisable() {
        pendingReversal = false;
        strafeArmed = false;
        strafeTicksLeft = -1;
        jumpArmed = false;
        jumpTicksLeft = -1;
        lagPending = false;
        lagTicksLeft = 0;
        hypixelAbsorbed = false;
    }

    /**
     * Called by {@link com.boss.pvp.client.mixin.VelocityMixin} on the network thread the
     * moment a ClientboundSetEntityMotionPacket targeting the local player is received.
     *
     * @return true if the packet should be cancelled (i.e. we're taking over applying it).
     */
    public boolean onMotionPacket(double x, double y, double z) {
        if (!PvpUtil.roll(integer("chance"))) return false;

        boolean isFallDamage = x == 0.0 && z == 0.0 && y < 0.0;

        switch (choice("mode")) {
            case "Modify" -> {
                int keepH = integer("horizontal");
                queueImmediate(VelocityMath.scale(x, keepH), VelocityMath.scale(y, integer("vertical")),
                               VelocityMath.scale(z, keepH));
                return true;
            }
            case "Reversal" -> {
                pendingReversal = true;
                reversalTicks = 0;
                // Let the original motion apply this tick; we reverse it a few ticks later.
                return false;
            }
            case "Strafe" -> {
                strafeArmed = true;
                strafeTicksLeft = integer("strafeDelay");
                return false;
            }
            case "JumpReset" -> {
                if (!isFallDamage) {
                    jumpArmed = true;
                    jumpTicksLeft = integer("jumpResetDelay");
                }
                return false;
            }
            case "Lag" -> {
                lagPending = true;
                lagTicksLeft = integer("lagTicks");
                lagX = x; lagY = y; lagZ = z;
                return true;
            }
            case "Hypixel" -> {
                Minecraft mc = Minecraft.getInstance();
                LocalPlayer p = mc.player;
                if (p != null && !p.onGround()) {
                    if (!hypixelAbsorbed) {
                        hypixelAbsorbed = true;
                        return true;
                    }
                }
                queueImmediate(0.0, y, 0.0, /*keepCurrentXZ*/ true);
                return true;
            }
            case "Dexland" -> {
                Minecraft mc = Minecraft.getInstance();
                LocalPlayer p = mc.player;
                dexlandCount++;
                boolean withinWindow = System.currentTimeMillis() - dexlandLastAttackMs <= 8000;
                dexlandLastAttackMs = System.currentTimeMillis();
                if (p != null && p.hurtTime > 0 && dexlandCount % 4 == 0 && withinWindow) {
                    double f = integer("reduceFactor") / 100.0;
                    queueImmediate(x * f, y, z * f);
                    return true;
                }
                return false;
            }
            case "Hylex" -> {
                // Hylex's staged reduction fires off hurtTime windows, which we don't see
                // here (this is the raw packet before hurtTime updates) — approximate with
                // a flat strong reduction plus jump-reset, close to their net effect.
                queueImmediate(x * 0.11, y, z * 0.11);
                jumpArmed = true;
                jumpTicksLeft = 0;
                return true;
            }
            case "BlocksMC" -> {
                // Cancel outright; BlocksMC's own bypass double-sneaks the server into
                // dropping the knockback, which needs a raw packet send hook we don't have
                // yet. Cancelling still denies the client-side motion.
                return true;
            }
            case "AAC4.4.2" -> {
                Minecraft mc = Minecraft.getInstance();
                LocalPlayer p = mc.player;
                if (p != null && p.hurtTime > 0 && !p.onGround()) {
                    double f = integer("reduceFactor") / 100.0;
                    queueImmediate(x * f, y, z * f);
                    return true;
                }
                return false;
            }
            case "Intave" -> {
                Minecraft mc = Minecraft.getInstance();
                LocalPlayer p = mc.player;
                boolean withinWindow = System.currentTimeMillis() - intaveLastAttackMs <= 2000;
                intaveLastAttackMs = System.currentTimeMillis();
                if (p != null && p.hurtTime >= 5 && p.hurtTime <= 7 && withinWindow) {
                    double f = integer("reduceFactor") / 100.0;
                    queueImmediate(x * f, y, z * f);
                    jumpArmed = true;
                    jumpTicksLeft = 0;
                    return true;
                }
                return false;
            }
            default -> {
                return false;
            }
        }
    }

    private void queueImmediate(double x, double y, double z) {
        queueImmediate(x, y, z, false);
    }

    private void queueImmediate(double x, double y, double z, boolean keepCurrentXZ) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null) return;
        if (keepCurrentXZ) {
            Vec3 d = p.getDeltaMovement();
            p.setDeltaMovement(d.x, y, d.z);
        } else {
            p.setDeltaMovement(x, y, z);
        }
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        if (p.onGround()) hypixelAbsorbed = false;

        if (pendingReversal) {
            Vec3 d = p.getDeltaMovement();
            if (d.lengthSqr() == 0.0) {
                pendingReversal = false;
            } else if (++reversalTicks >= integer("reversalDelay")) {
                int s = integer("reversalStrength");
                p.setDeltaMovement(VelocityMath.reverse(d.x, s), d.y, VelocityMath.reverse(d.z, s));
                pendingReversal = false;
            }
        }

        if (strafeArmed && strafeTicksLeft-- <= 0) {
            strafeArmed = false;
            Vec3 d = p.getDeltaMovement();
            double speed = VelocityMath.scale(VelocityMath.horizontalSpeed(d.x, d.z), integer("strafeStrength"));
            float yaw = p.getYRot();
            p.setDeltaMovement(VelocityMath.dirX(yaw) * speed, d.y, VelocityMath.dirZ(yaw) * speed);
        }

        if (jumpArmed) {
            if (jumpTicksLeft-- <= 0 && p.onGround() && p.isSprinting()) {
                p.jumpFromGround();
                jumpArmed = false;
            }
        }

        if (lagPending && --lagTicksLeft <= 0) {
            lagPending = false;
            p.setDeltaMovement(lagX, lagY, lagZ);
            if (bool("lagJumpReset") && p.onGround() && p.isSprinting()) {
                p.jumpFromGround();
            }
        }
    }
}
