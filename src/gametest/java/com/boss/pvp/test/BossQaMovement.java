package com.boss.pvp.test;

import autismclient.modules.Module;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Bounded real ladder input trials; all displacement is read from the integrated server. */
@SuppressWarnings("UnstableApiUsage")
final class BossQaMovement implements AutoCloseable {
    private final ClientGameTestContext context;
    private final BossQaNativeFixtures fixtures;
    private final Module ladder;
    private final boolean previouslyEnabled;
    private final String previousSpeed;
    private boolean closed;

    BossQaMovement(ClientGameTestContext context, BossQaNativeFixtures fixtures) {
        this.context = context;
        this.fixtures = fixtures;
        ladder = context.computeOnClient(client -> BossQaModules.require("fastladder"));
        previouslyEnabled = context.computeOnClient(client -> ladder.isEnabled());
        previousSpeed = context.computeOnClient(client -> ladder.value("speed"));
    }

    void proveLadderAscent() {
        double control = measure(false, false);
        double boosted = measure(true, false);
        System.out.println("[BOSS-QA] FastLadder real server y deltas: disabled=" + control + " enabled=" + boosted);
        BossQaIsolation.require(control > 0.1 && boosted > control + 0.15,
                "FastLadder did not increase server-confirmed ascent beyond identical native disabled-control input");
    }

    void proveSneakCancel() {
        double control = measure(false, true);
        double cancelled = measure(true, true);
        System.out.println("[BOSS-QA] FastLadder sneak real server y deltas: disabled=" + control + " enabled=" + cancelled);
        // Allow less than two vanilla ladder movement ticks of asynchronous server/client snapshot skew.
        BossQaIsolation.require(Math.abs(cancelled - control) <= 0.35,
                "Sneaking FastLadder retained a server-visible boost versus the same disabled sneak control");
        context.runOnClient(client -> BossQaIsolation.require(!ladder.isEnabled()
                && !client.options.keyJump.isDown() && !client.options.keyShift.isDown(),
                "Native ladder input lease or module remained active after cancellation trials"));
    }

    private double measure(boolean enabled, boolean sneak) {
        context.runOnClient(client -> {
            ladder.setValueTransient("speed", "0.5");
            ladder.setEnabled(enabled);
            BossQaIsolation.require(ladder.isEnabled() == enabled, "FastLadder trial toggle was not accepted");
        });
        try {
            fixtures.startLadderTrial();
            try (BossQaInputLease input = new BossQaInputLease(context)) {
                if (sneak) input.hold(options -> options.keyShift);
                context.waitTick();
                context.runOnClient(client -> BossQaIsolation.require(client.player.onClimbable()
                        && client.options.keyShift.isDown() == sneak,
                        "Backed-ladder/sneak precondition was not reached through native input"));
                double before = fixtures.localPositionY();
                input.hold(options -> options.keyJump);
                context.waitTicks(10);
                double after = fixtures.localPositionY();
                BossQaIsolation.require(Double.isFinite(before) && Double.isFinite(after)
                        && after >= 81 && after < 89, "Ladder trial left its bounded native arena");
                return after - before;
            }
        } finally {
            context.runOnClient(client -> ladder.setEnabled(false));
            fixtures.resetLocalToPlatform();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        try {
            context.runOnClient(client -> {
                ladder.setEnabled(false);
                ladder.setValueTransient("speed", previousSpeed);
                ladder.setEnabled(previouslyEnabled);
            });
        } finally {
            closed = true;
        }
    }
}
