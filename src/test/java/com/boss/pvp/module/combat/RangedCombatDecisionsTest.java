package com.boss.pvp.module.combat;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RangedCombatDecisionsTest {
    @Test
    void pearlHotkeyTriggersOnlyOnPressEdges() {
        assertTrue(RangedCombatDecisions.risingEdge(false, true));
        assertFalse(RangedCombatDecisions.risingEdge(true, true));
        assertFalse(RangedCombatDecisions.risingEdge(true, false));
        assertFalse(RangedCombatDecisions.risingEdge(false, false));
    }

    @Test
    void healthGateRejectsThresholdAndInvalidInput() {
        assertTrue(RangedCombatDecisions.healthy(13.0, 12.0));
        assertFalse(RangedCombatDecisions.healthy(12.0, 12.0));
        assertFalse(RangedCombatDecisions.healthy(Double.NaN, 12.0));
        assertFalse(RangedCombatDecisions.healthy(20.0, 4.0));
        assertFalse(RangedCombatDecisions.healthy(Double.POSITIVE_INFINITY, 12.0));
    }

    @Test
    void fireballMustActuallyApproachWithinThePredictionWindow() {
        Vec3 toward = new Vec3(5, 0, 0);
        assertTrue(RangedCombatDecisions.incoming(toward, new Vec3(1, 0, 0), 8.0, 1.0));
        assertFalse(RangedCombatDecisions.incoming(toward, new Vec3(-1, 0, 0), 8.0, 1.0));
        assertFalse(RangedCombatDecisions.incoming(toward, Vec3.ZERO, 8.0, 1.0));
        assertFalse(RangedCombatDecisions.incoming(toward, new Vec3(1, 0, 0), 2.0, 1.0));
        assertFalse(RangedCombatDecisions.incoming(new Vec3(5, 3, 0), new Vec3(1, 0, 0), 8.0, 1.0));
        assertFalse(RangedCombatDecisions.incoming(toward, new Vec3(Double.NaN, 0, 0), 8.0, 1.0));
        assertFalse(RangedCombatDecisions.incoming(toward, new Vec3(1, 0, 0), 8.0, Double.NaN));
    }

    @Test
    void chargeUsesRealCurrentDrawTicks() {
        assertFalse(RangedCombatDecisions.chargeReady(19, 20));
        assertTrue(RangedCombatDecisions.chargeReady(20, 20));
        assertTrue(RangedCombatDecisions.chargeReady(10, 10));
        assertFalse(RangedCombatDecisions.chargeReady(0, 0));
    }
}
