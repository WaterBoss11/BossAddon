package com.boss.pvp.util.pvp;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RetainedProjectileMathTest {

    private static final double EPS = 1.0e-7;

    private static void assertVector(Vec3 expected, Vec3 actual) {
        assertNotNull(actual);
        assertEquals(expected.x, actual.x, EPS);
        assertEquals(expected.y, actual.y, EPS);
        assertEquals(expected.z, actual.z, EPS);
    }

    @Test
    void throwableUsesNativeSpawnInheritedMotionAndGravityThenDragBeforeMovement() {
        Vec3 eyes = new Vec3(1.0, 2.0, 3.0);
        assertVector(new Vec3(1.0, 1.9, 3.0), ProjectileMath.origin(ProjectileMath.Kind.THROWABLE, eyes, 0.0f));
        Vec3 velocity = ProjectileMath.launchVelocity(ProjectileMath.Kind.THROWABLE, 0.0f, 0.0f, new Vec3(0.2, 0.1, -0.1));
        assertVector(new Vec3(0.2, 0.1, 1.4), velocity);
        assertVector(velocity.add(0.0, -0.03, 0.0).scale((double) 0.99f),
            ProjectileMath.positionAt(ProjectileMath.Kind.THROWABLE, Vec3.ZERO, velocity, 1.0));
    }

    @Test
    void anExistingPearlContinuesFromItsCurrentVelocityInsteadOfRelaunching() {
        Vec3 origin = new Vec3(0.0, 8.0, 0.0);
        Vec3 velocity = new Vec3(0.7, -0.5, 0.2);
        Vec3 firstVelocity = velocity.add(0.0, -0.03, 0.0).scale((double) 0.99f);
        Vec3 firstPosition = ProjectileMath.positionAt(ProjectileMath.Kind.THROWABLE, origin, velocity, 1.0);
        Vec3 continued = ProjectileMath.positionAt(ProjectileMath.Kind.THROWABLE, firstPosition, firstVelocity, 1.0);
        assertVector(ProjectileMath.positionAt(ProjectileMath.Kind.THROWABLE, origin, velocity, 2.0), continued);
    }

    @Test
    void rodUsesForwardSpawnOffsetPitchDependentSpeedAndAfterMoveDrag() {
        assertVector(new Vec3(0.0, 2.0, 0.3), ProjectileMath.origin(ProjectileMath.Kind.FISHING_ROD, new Vec3(0.0, 2.0, 0.0), 0.0f));
        Vec3 flat = ProjectileMath.launchVelocity(ProjectileMath.Kind.FISHING_ROD, 0.0f, 0.0f, new Vec3(10.0, 10.0, 10.0));
        assertVector(new Vec3(0.0, 0.0, 1.1), flat);
        Vec3 angled = ProjectileMath.launchVelocity(ProjectileMath.Kind.FISHING_ROD, 0.0f, -45.0f, Vec3.ZERO);
        assertEquals(0.6 + 0.5 * Math.sqrt(2.0), angled.length(), EPS);
        assertVector(flat.add(0.0, -0.03, 0.0), ProjectileMath.positionAt(ProjectileMath.Kind.FISHING_ROD, Vec3.ZERO, flat, 1.0));
        Vec3 second = flat.add(0.0, -0.03, 0.0).scale(0.92).add(0.0, -0.03, 0.0);
        assertVector(flat.add(0.0, -0.03, 0.0).add(second), ProjectileMath.positionAt(ProjectileMath.Kind.FISHING_ROD, Vec3.ZERO, flat, 2.0));
    }

    @Test
    void lowArcSolutionsActuallyReachStationaryAndMovingTargetsForBothKinds() {
        for (ProjectileMath.Kind kind : ProjectileMath.Kind.values()) {
            Vec3 origin = new Vec3(0.0, 1.5, 0.0);
            Vec3 target = new Vec3(0.0, 0.9, 5.0);
            for (Vec3 targetVelocity : new Vec3[] {Vec3.ZERO, new Vec3(0.12, 0.02, 0.0)}) {
                Vec3 inherited = kind == ProjectileMath.Kind.THROWABLE ? new Vec3(0.08, 0.0, 0.0) : Vec3.ZERO;
                var shot = ProjectileMath.solve(kind, origin, inherited, target, targetVelocity, 30);
                assertNotNull(shot, kind.toString());
                assertTrue(shot.ticks() > 0.0 && shot.ticks() <= 30.0);
                Vec3 expected = target.add(ProjectileMath.leadOffset(targetVelocity, shot.ticks()));
                Vec3 actual = ProjectileMath.positionAt(kind, origin, shot.velocity(), shot.ticks());
                assertTrue(actual.distanceTo(expected) < 0.001, kind + " intercept misses by " + actual.distanceTo(expected));
            }
        }
    }

    @Test
    void leadAndFlightHorizonAreBoundedAndImpossibleShotsAreRejected() {
        assertEquals(15.0, ProjectileMath.leadOffset(new Vec3(10.0, 0.0, 0.0), 1000.0).length(), EPS);
        assertVector(Vec3.ZERO, ProjectileMath.leadOffset(new Vec3(1.0, 0.0, 0.0), -1.0));
        assertVector(ProjectileMath.positionAt(ProjectileMath.Kind.THROWABLE, Vec3.ZERO, new Vec3(1.5, 0.0, 0.0), 80.0),
            ProjectileMath.positionAt(ProjectileMath.Kind.THROWABLE, Vec3.ZERO, new Vec3(1.5, 0.0, 0.0), 1000.0));
        assertNull(ProjectileMath.solve(ProjectileMath.Kind.THROWABLE, Vec3.ZERO, Vec3.ZERO, new Vec3(0.0, 100.0, 100.0), Vec3.ZERO, 40));
        assertNull(ProjectileMath.solve(ProjectileMath.Kind.THROWABLE, Vec3.ZERO, Vec3.ZERO, new Vec3(0.0, 1.0, 5.0), Vec3.ZERO, 0));
        assertNull(ProjectileMath.solve(ProjectileMath.Kind.THROWABLE, new Vec3(Double.NaN, 0.0, 0.0), Vec3.ZERO,
            new Vec3(0.0, 1.0, 5.0), Vec3.ZERO, 40));
    }

    @Test
    void missingWorldOrTargetFailsClosedInsteadOfAuthorizingAThrow() {
        assertNull(ProjectileMath.trace(null, null, ProjectileMath.Kind.THROWABLE,
            Vec3.ZERO, new Vec3(0.0, 0.0, 1.5), 40));
        assertFalse(ProjectileMath.clearShot(null, null, null, ProjectileMath.Kind.THROWABLE,
            Vec3.ZERO, new Vec3(0.0, 0.0, 1.5), 40));
    }

    @Test
    void paddedTargetWouldFalselyAuthorizeAGrazingMissSoAimRequiresTheRealBox() {
        AABB box = new AABB(-0.3, -0.3, 1.7, 0.3, 0.3, 2.3);
        Vec3 from = new Vec3(0.45, 0.0, 0.0);
        Vec3 to = new Vec3(0.45, 0.0, 4.0);
        assertNull(ProjectileMath.movingBoxHit(box, Vec3.ZERO, 0.0, from, to));
        assertNotNull(ProjectileMath.movingBoxHit(box.inflate((double) 0.3f), Vec3.ZERO, 0.0, from, to));
    }

    @Test
    void movingBlockerIsTestedInRelativeSpaceRatherThanOnlyItsPresentBox() {
        AABB crossing = new AABB(1.1, -0.2, 0.9, 1.5, 0.2, 1.1);
        Vec3 hit = ProjectileMath.movingBoxHit(crossing, new Vec3(-1.5, 0.0, 0.0), 0.0,
            Vec3.ZERO, new Vec3(1.0, 0.0, 2.0));
        assertNotNull(hit);
        assertTrue(hit.z > 0.8 && hit.z < 1.2);
        assertNull(ProjectileMath.movingBoxHit(crossing, new Vec3(1.5, 0.0, 0.0), 0.0,
            Vec3.ZERO, new Vec3(1.0, 0.0, 2.0)));
    }
}
