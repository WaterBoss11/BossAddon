package com.boss.pvp.module.misc;

import com.boss.pvp.BossPvpAddon;

import autismclient.api.AutismAddons;
import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Velocity crash guard — a purely DEFENSIVE, local-only safeguard.
 *
 * <p>Some servers send absurd or malformed velocity for the local player (real crash reports carried
 * ~1.8e38 / 2.8e38 / 2.1e38 on X/Y/Z). Those values overflow Minecraft's own position/section math and crash
 * the client deep inside vanilla's {@code EntitySectionStorage}/collision code — reproducible even with
 * Lithium disabled, so it is not an optimisation-mod bug. When this guard is on (the default), incoming motion
 * for the local player is capped to a sane maximum (see {@link com.boss.pvp.util.pvp.VelocityMath#clampMotion})
 * before vanilla ever processes it.
 *
 * <p>This is the same honest, defensive category as Anti-Knockback's disclosure — it protects your own client
 * from a malformed value. It never changes anything you SEND to the server and never deceives it; it only
 * refuses to feed an impossible motion into your own client's physics. Clamps only trigger on values orders of
 * magnitude beyond any legitimate speed (elytra, fireworks, riptide, pearls, TNT launches), so real gameplay
 * is never affected.
 */
public final class VelocityClampModule extends Module {

    private long clampCount = 0;
    private boolean notifiedThisSession = false;

    public VelocityClampModule() {
        super(BossPvpAddon.ID + ":velocityclamp", "Velocity Crash Guard",
            AutismAddons.modules().registerCategory("Client"),
            "Cap extreme incoming velocity so a bad value can't crash your client.");
        add(new BoolSetting("clamp", "Clamp extreme velocity", true)
            .description("Caps absurd or malformed knockback/velocity from the server to a safe maximum, "
                + "protecting your client from a crash caused by overflow in vanilla's own movement math. "
                + "Only triggers on values far beyond any real speed, so legit elytra/pearl/TNT motion is "
                + "never affected. Purely local and defensive — it never changes what you send to the "
                + "server. Leave on."));
    }

    /** Whether the safeguard is active (default on). Read from the motion-packet mixin. */
    public boolean active() {
        return bool("clamp");
    }

    /** How many times an out-of-range motion has been clamped away this session (for diagnostics). */
    public long clampCount() {
        return clampCount;
    }

    /**
     * Called by the motion-packet mixin each time it clamped an out-of-range incoming velocity. Counts the
     * event, logs the raw values for debugging, and tells the player once per session that they were protected.
     */
    public void onClamped(double x, double y, double z) {
        clampCount++;
        System.out.println("[BossPvP/crashguard] clamped extreme incoming velocity #" + clampCount
            + " (raw x=" + x + " y=" + y + " z=" + z + ")");
        if (!notifiedThisSession) {
            notifiedThisSession = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                    "§6[BossAddon]§r §7Blocked an extreme velocity value from the server (crash safeguard)."));
            }
        }
    }
}
