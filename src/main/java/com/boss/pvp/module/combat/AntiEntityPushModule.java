package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.VelocityMath;

import autismclient.modules.Module;
import autismclient.api.module.*;

public final class AntiEntityPushModule extends Module {

    public AntiEntityPushModule() {
        super(BossPvpAddon.ID + ":antientitypush", "AntiEntityPush", "Stops or reduces mobs and players pushing you around.");

        // How incoming push is handled. Default "Cancel" keeps the module's original flat-block behaviour.
        add(new ChoiceSetting("mode", "Mode", "Cancel", "Cancel", "Modify")
            .description("Cancel fully blocks push velocity (the original behaviour). Modify keeps a percentage "
                + "of it instead. Note: reducing push rather than fully cancelling it is STILL an "
                + "anticheat-detectable movement change — same category as the full cancel, not a safer bypass.")
            .group("General"));

        // Modify mode: the same horizontal/vertical "keep %" pattern the Velocity module uses for knockback.
        add(new IntSetting("horizontal", "Keep horizontal push", 40, 0, 100, 1)
            .formatter(v -> v + "%")
            .description("Modify mode only: how much sideways push to keep (0% = fully cancelled, 100% = unchanged).")
            .group("Modify"));
        add(new IntSetting("vertical", "Keep vertical push", 100, 0, 100, 1)
            .formatter(v -> v + "%")
            .description("Modify mode only: how much up/down push to keep.")
            .group("Modify"));

        add(new BoolSetting("onlyWhileSurrounded", "Only while Surround active", false)
            .description("Only stop pushes while the Surround module is turned on.").group("General"));
    }

    public boolean onlyWhileSurrounded() { return bool("onlyWhileSurrounded"); }

    /** The push velocity to actually apply to the player for a raw incoming push, per the current mode. */
    public double[] pushDelta(double x, double y, double z) {
        return pushDelta(choice("mode"), x, y, z, integer("horizontal"), integer("vertical"));
    }

    /**
     * Pure push-reduction math (extracted for unit testing; reuses {@link VelocityMath#scale} rather than
     * re-implementing the percentage math). {@code "Cancel"} returns a zero vector — the push is fully blocked,
     * exactly the module's original behaviour. {@code "Modify"} keeps a percentage per axis: the horizontal
     * keep on X and Z, the vertical keep on Y.
     */
    public static double[] pushDelta(String mode, double x, double y, double z, int keepHorizontal, int keepVertical) {
        if (!"Modify".equals(mode)) {
            return new double[]{0.0, 0.0, 0.0};   // Cancel (default): block the push entirely
        }
        return new double[]{
            VelocityMath.scale(x, keepHorizontal),
            VelocityMath.scale(y, keepVertical),
            VelocityMath.scale(z, keepHorizontal),
        };
    }
}
