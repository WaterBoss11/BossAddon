package com.boss.pvp;

import com.boss.pvp.util.pvp.PlayerSimulation;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the pure physics core of {@link PlayerSimulation#simulate} — friction, gravity, and
 * trajectory shape. World/entity-coupled parts ({@code predictPosition(Player,...)} and the per-tick cache) need
 * a live player entity, which a plain unit test cannot build, so those two cache cases are not covered here.
 */
class PlayerSimulationTest {

    private static final double EPS = 1.0e-9;
    private static final Vec3 ORIGIN = new Vec3(0, 64, 0);
    private static final Vec3 STILL = Vec3.ZERO;

    @Test
    void testGroundFriction() {
        // A stationary player on the ground stays put (ground holds Y, no horizontal velocity to decay).
        List<Vec3> traj = PlayerSimulation.simulate(ORIGIN, STILL, true, 5);
        for (Vec3 p : traj) {
            assertEquals(0.0, p.x, EPS);
            assertEquals(64.0, p.y, EPS);
            assertEquals(0.0, p.z, EPS);
        }
    }

    @Test
    void testGravityAcceleration() {
        // An airborne player falls, and the drop GROWS each tick (accelerating under gravity).
        List<Vec3> traj = PlayerSimulation.simulate(ORIGIN, STILL, false, 3);
        double dropTick0 = ORIGIN.y - traj.get(0).y;
        double dropTick1 = traj.get(0).y - traj.get(1).y;
        assertTrue(dropTick0 > 0.0, "the player should be falling");
        assertTrue(dropTick1 > dropTick0, "the fall should accelerate (later drop > earlier drop)");
    }

    @Test
    void testHorizontalDecay() {
        // A player sliding on the ground decelerates: each tick's horizontal step is smaller than the last.
        List<Vec3> traj = PlayerSimulation.simulate(ORIGIN, new Vec3(1, 0, 0), true, 3);
        double step0 = traj.get(0).x - ORIGIN.x;
        double step1 = traj.get(1).x - traj.get(0).x;
        assertTrue(step0 > 0.0, "the player should keep moving forward");
        assertTrue(step1 < step0, "friction should shrink each step (deceleration)");
    }

    @Test
    void testTrajectoryLength() {
        assertEquals(4, PlayerSimulation.simulate(ORIGIN, STILL, false, 4).size(), "N ticks -> N positions");
        assertTrue(PlayerSimulation.simulate(ORIGIN, STILL, false, 0).isEmpty(), "0 ticks -> empty trajectory");
    }

    @Test
    void testFirstTickConsistentAcrossHorizons() {
        // The first predicted tick must be identical regardless of how many ticks ahead we simulate.
        Vec3 vel = new Vec3(0.3, 0.1, -0.2);
        Vec3 firstOfLong = PlayerSimulation.simulate(ORIGIN, vel, false, 5).get(0);
        Vec3 firstOfShort = PlayerSimulation.simulate(ORIGIN, vel, false, 1).get(0);
        assertEquals(firstOfShort.x, firstOfLong.x, EPS);
        assertEquals(firstOfShort.y, firstOfLong.y, EPS);
        assertEquals(firstOfShort.z, firstOfLong.z, EPS);
    }
}
