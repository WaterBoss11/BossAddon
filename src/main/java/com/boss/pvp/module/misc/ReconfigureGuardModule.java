package com.boss.pvp.module.misc;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.flag.FlagReporter;
import com.boss.pvp.util.ReconfigureLoopDetector;

import autismclient.api.AutismAddons;
import autismclient.modules.Module;
import autismclient.api.module.BoolSetting;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;

/**
 * Reconfigure-loop guard — a purely DEFENSIVE, local-only safeguard.
 *
 * <p>A malicious server can repeatedly force the client from PLAY back into the configuration protocol phase
 * right after it loads in, trapping the player in an endless "Loading terrain &rarr; Reconfiguring" loop that
 * never lets them actually play. This guard OBSERVES those play&rarr;configuration transitions (see the
 * reconfigure mixin) and, if they repeat too many times within a short window, cleanly disconnects the local
 * client so the player isn't stuck indefinitely.
 *
 * <p><b>Detect-and-disconnect only, never deceive-and-continue.</b> It never alters, forges, or suppresses any
 * packet sent to the server — the server's reconfigure request is always handled normally. The guard only
 * watches the transitions and, on a detected loop, closes the LOCAL connection (the same "observe and leave"
 * category as the crash/kick flag reporting). Default on; leave it on.
 */
public final class ReconfigureGuardModule extends Module {

    // 3 reconfigures within 12s is a loop. A single legitimate reconfigure (e.g. a resource-pack reload), or a
    // few spread out over minutes, never trips this — the window evicts stale events (see the detector).
    private static final int THRESHOLD = 3;
    private static final long WINDOW_MS = 12_000L;
    private static final Component REASON = Component.literal(
        "[BossAddon] Disconnected: detected a repeated reconfigure loop — this may be a server issue or targeted abuse");

    private final ReconfigureLoopDetector detector = new ReconfigureLoopDetector(THRESHOLD, WINDOW_MS);

    public ReconfigureGuardModule() {
        super(BossPvpAddon.ID + ":reconfigureguard", "Reconfigure Loop Guard",
            AutismAddons.modules().registerCategory("Client"),
            "Auto-disconnect if a server traps you in a reconfigure loop.");
        add(new BoolSetting("guard", "Guard against reconfigure loops", true)
            .description("If a server repeatedly forces your client back into the configuration phase right after "
                + "you load in (an endless \"Loading terrain / Reconfiguring\" loop), disconnect cleanly instead "
                + "of leaving you stuck. Purely local and defensive — it only watches the phase transitions and "
                + "leaves; it never changes or blocks anything sent to the server. Leave on."));
    }

    /** Whether the safeguard is active (default on). Read from the reconfigure mixin. */
    public boolean active() {
        return bool("guard");
    }

    /**
     * Called by the mixin on each play&rarr;configuration transition. Records it; on a detected loop, tags the
     * flag reporter and cleanly disconnects the local client (on the render thread). Synchronized because these
     * transitions can arrive on the network thread.
     */
    public synchronized void onReconfigure(Connection conn) {
        if (!detector.record(System.currentTimeMillis())) return;
        detector.reset();   // don't re-fire on the same burst
        System.out.println("[boss-pvp/reconfigure-guard] repeated reconfigure loop detected — disconnecting");
        FlagReporter.markReconfigureLoop();   // so the disconnect that follows is reported as a reconfigure loop
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                if (conn != null && conn.isConnected()) conn.disconnect(REASON);
            } catch (Throwable ignored) {
                // a safeguard must never make things worse
            }
        });
    }
}
