package com.boss.pvp.module.automation;

import org.junit.jupiter.api.Test;

import static com.boss.pvp.module.automation.AutoHookModule.RodCycle.Action.*;
import static org.junit.jupiter.api.Assertions.*;

class RetainedAutoHookStateTest {

    @Test
    void waitsForARealBobberBeforePullModeCanReel() {
        var cycle = new AutoHookModule.RodCycle(1000L);
        assertEquals(WAIT, cycle.update(null, 1050L, false, false, true, false, true, true));
        assertEquals(WAIT, cycle.update(null, 1900L, false, false, true, false, true, true));
        Object bobber = new Object();
        assertEquals(REEL, cycle.update(bobber, 1950L, false, false, true, false, true, true));
        assertTrue(cycle.owns(bobber));
        assertFalse(cycle.canReel());
    }

    @Test
    void missedSpawnAcknowledgementTimesOutWithoutCastingAgain() {
        var cycle = new AutoHookModule.RodCycle(1000L);
        assertEquals(FINISH, cycle.update(null, 2000L, false, false, true, false, true, false));
        assertFalse(cycle.owns(null));
    }

    @Test
    void flyingRodDoesNotReelAtTheOldFixedQuarterSecondDelay() {
        var cycle = new AutoHookModule.RodCycle(1000L);
        Object bobber = new Object();
        assertEquals(WAIT, cycle.update(bobber, 1100L, false, false, true, false, true, false));
        assertEquals(WAIT, cycle.update(bobber, 1350L, false, false, true, false, true, false));
        assertEquals(REEL, cycle.update(bobber, 1400L, true, false, true, false, true, false));
        assertEquals(WAIT, cycle.update(bobber, 1450L, true, false, true, false, true, false));
        assertEquals(FINISH, cycle.update(null, 1500L, false, false, true, false, true, false));
    }

    @Test
    void vanishedOrReplacedBobbersCannotCauseAnAccidentalRecast() {
        var cycle = new AutoHookModule.RodCycle(0L);
        Object bobber = new Object();
        assertEquals(WAIT, cycle.update(bobber, 50L, false, false, true, false, true, false));
        assertEquals(FINISH, cycle.update(new Object(), 100L, false, false, true, false, true, false));
        assertFalse(cycle.owns(new Object()));
    }

    @Test
    void stallsRangeLossAndFlightTimeoutReelOnceThenBoundTheAcknowledgementWait() {
        for (int reason = 0; reason < 3; reason++) {
            var cycle = new AutoHookModule.RodCycle(1000L);
            Object bobber = new Object();
            assertEquals(WAIT, cycle.update(bobber, 1050L, false, false, true, false, true, false));
            long now = reason == 2 ? 2550L : 1250L;
            assertEquals(REEL, cycle.update(bobber, now, false, false, reason != 1, reason == 0, true, false));
            assertEquals(WAIT, cycle.update(bobber, now + 100L, false, false, true, false, true, false));
            assertEquals(FINISH, cycle.update(bobber, now + 500L, false, false, true, false, true, false));
        }
    }

    @Test
    void protectedHooksAndDisabledReelingFinishWithoutPullingAnyone() {
        Object bobber = new Object();
        var protectedCycle = new AutoHookModule.RodCycle(0L);
        assertEquals(FINISH, protectedCycle.update(bobber, 50L, true, true, true, false, true, false));
        var noReelCycle = new AutoHookModule.RodCycle(0L);
        assertEquals(FINISH, noReelCycle.update(bobber, 50L, true, false, true, false, false, true));
    }

    @Test
    void onAttackRequiresOneNewRecentRecordedAttackNotAnAttackKeyOrOldTarget() {
        assertFalse(AutoHookModule.RodCycle.newAttack(0L, 0L, 1000L, 1500L));
        assertFalse(AutoHookModule.RodCycle.newAttack(1000L, 1000L, 1200L, 1500L));
        assertFalse(AutoHookModule.RodCycle.newAttack(1000L, 900L, 2600L, 1500L));
        assertFalse(AutoHookModule.RodCycle.newAttack(1000L, 900L, 999L, 1500L));
        assertTrue(AutoHookModule.RodCycle.newAttack(1000L, 900L, 1000L, 1500L));
    }

    @Test
    void comboCountsRealHurtHealthLossNotInitializationOrAbsorptionExpiry() {
        assertFalse(AutoHookModule.RodCycle.lostHealth(-1.0f, 20.0f, 10));
        assertFalse(AutoHookModule.RodCycle.lostHealth(24.0f, 20.0f, 0));
        assertFalse(AutoHookModule.RodCycle.lostHealth(20.0f, 20.0f, 10));
        assertTrue(AutoHookModule.RodCycle.lostHealth(20.0f, 17.0f, 10));
    }
}
