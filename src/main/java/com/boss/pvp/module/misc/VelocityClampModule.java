package com.boss.pvp.module.misc;

import com.boss.pvp.BossPvpAddon;

import autismclient.api.AutismAddons;
import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Packet crash guard — a purely DEFENSIVE, local-only safeguard against malformed incoming numeric data.
 *
 * <p>Some servers send absurd or malformed numbers for the local player or nearby entities (real crash reports
 * carried per-axis velocity around ~1.8e38 / 2.8e38 / 2.1e38). Those values overflow Minecraft's own
 * position/section math and crash the client deep inside vanilla's {@code EntitySectionStorage}/collision code —
 * reproducible even with Lithium disabled, so it is not an optimisation-mod bug. When this guard is on (the
 * default), incoming packet fields are checked against a shared sanity ceiling
 * ({@link com.boss.pvp.util.NumericSanity}) before vanilla processes them:
 * <ul>
 *   <li>an out-of-range <b>velocity</b> for the local player is clamped to a sane magnitude (direction kept) —
 *       see {@code VelocityMixin}; and</li>
 *   <li>a packet carrying an out-of-range <b>position/velocity</b> (explosion knockback, entity/player teleports,
 *       position syncs, entity spawns) is dropped before the client applies it — see {@code PacketSanityMixin}.</li>
 * </ul>
 * This generalises what began as a single motion-packet clamp: new overflow vectors are caught by the same shared
 * check instead of each needing a new one-off fix.
 *
 * <p>This is the same honest, defensive category as Anti-Knockback's disclosure — it protects your own client
 * from a malformed value. It never changes anything you SEND to the server and never fakes an acknowledgement; it
 * only refuses to feed an impossible number into your own client's math. Guards only trigger on values orders of
 * magnitude beyond anything legitimate (the world border sits near 3e7 blocks; real speeds stay under ~100
 * blocks/tick), so real gameplay is never affected.
 */
public final class VelocityClampModule extends Module {

    private long guardCount = 0;
    private boolean notifiedThisSession = false;

    public VelocityClampModule() {
        // Module id stays "velocityclamp" so existing configs and keybinds are preserved; the display name is the
        // broader "Packet Crash Guard" now that the guard covers more than just the velocity packet.
        super(BossPvpAddon.ID + ":velocityclamp", "Packet Crash Guard",
            AutismAddons.modules().registerCategory("Client"),
            "Reject or clamp absurd incoming packet values so a bad number can't crash your client.");
        add(new BoolSetting("clamp", "Guard against crash values", true)
            .description("Catches malformed or absurd numeric values from the server — extreme knockback/velocity, "
                + "and explosion/teleport/spawn positions far beyond the world border — that would otherwise "
                + "overflow vanilla's own movement and section math and crash your client. Out-of-range velocity "
                + "is clamped to a safe magnitude; a packet carrying an impossible position is dropped. Only "
                + "triggers on values far beyond anything legitimate, so real gameplay is never affected. Purely "
                + "local and defensive — it never changes what you send to the server. Leave on."));
    }

    /** Whether the safeguard is active (default on). Read from the packet mixins. */
    public boolean active() {
        return bool("clamp");
    }

    /** How many out-of-range values have been guarded away this session (for diagnostics). */
    public long guardCount() {
        return guardCount;
    }

    /**
     * Called by the motion-packet mixin each time it clamped an out-of-range incoming velocity for the local
     * player. Counts it, logs the raw values, and notifies the player once per session that they were protected.
     */
    public void onClamped(double x, double y, double z) {
        bosspvp$record("clamped extreme incoming velocity (raw x=" + x + " y=" + y + " z=" + z + ")");
    }

    /**
     * Called by the packet-sanity mixin each time it dropped a packet carrying an out-of-range position/velocity
     * (e.g. an explosion, teleport, position sync or entity spawn). {@code kind} names the packet for the log.
     */
    public void onRejected(String kind, String detail) {
        bosspvp$record("dropped " + kind + " with an out-of-range value (" + detail + ")");
    }

    private void bosspvp$record(String what) {
        guardCount++;
        System.out.println("[BossPvP/crashguard] " + what + " #" + guardCount);
        if (!notifiedThisSession) {
            notifiedThisSession = true;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal(
                    "§6[BossAddon]§r §7Blocked a malformed value from the server (crash safeguard)."));
            }
        }
    }
}
