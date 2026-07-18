package com.boss.pvp;

import com.boss.pvp.module.combat.KillAuraModule;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for KillAura's "Smart" target-priority weighting ({@link KillAuraModule#smartScoreOf}).
 * Lower score = higher priority. This is pure math (distance / effective-HP / crosshair-angle / hurt-time
 * in, weighted score out), so it needs no Minecraft world — the entity-coupled {@code smartScore} that
 * feeds it real values isn't covered here.
 */
class KillAuraSmartScoreTest {

    @Test
    void closerTargetIsHigherPriority() {
        // Everything else equal, the nearer target scores lower (focus first).
        assertTrue(KillAuraModule.smartScoreOf(2, 20, 0, 0) < KillAuraModule.smartScoreOf(5, 20, 0, 0),
            "a closer target should be prioritised");
    }

    @Test
    void lowerEffectiveHpIsHigherPriority() {
        // Same distance: the squishier target (would die sooner) is focused first.
        assertTrue(KillAuraModule.smartScoreOf(3, 4, 0, 0) < KillAuraModule.smartScoreOf(3, 20, 0, 0),
            "a lower-effective-HP target should be prioritised");
    }

    @Test
    void armourRaisesEffectiveHpSoTankierRanksLower() {
        // Two 10-HP targets; the one with more armour has higher effective HP and lower priority.
        double squishy = KillAuraModule.smartScoreOf(3, 10, 0, 0);
        double armoured = KillAuraModule.smartScoreOf(3, 10 + 20 * 0.5, 0, 0); // +20 armour points, *0.5 weight
        assertTrue(squishy < armoured, "the more-armoured (tankier) target should rank lower priority");
    }

    @Test
    void inViewTargetIsHigherPriority() {
        // Same distance and HP: the one closer to the crosshair (smaller angle) needs less rotation.
        assertTrue(KillAuraModule.smartScoreOf(3, 10, 0, 0) < KillAuraModule.smartScoreOf(3, 10, 90, 0),
            "an in-view target should be prioritised over one behind you");
    }

    @Test
    void hittableNowBeatsTemporarilyInvulnerable() {
        // hurtTime > 0 means the target just got hit and is briefly invulnerable — deprioritise it.
        assertTrue(KillAuraModule.smartScoreOf(3, 10, 0, 0) < KillAuraModule.smartScoreOf(3, 10, 0, 10),
            "a hittable target should be prioritised over a temporarily-invulnerable one");
    }

    @Test
    void closeLowHpBeatsFarFullHp() {
        // A close, nearly-dead target outranks a far, full-health one — the real burst-down pick.
        assertTrue(KillAuraModule.smartScoreOf(2, 4, 0, 0) < KillAuraModule.smartScoreOf(5, 20, 0, 0),
            "close + low HP should outrank far + full HP");
    }
}
