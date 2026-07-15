package com.boss.pvp;

import autismclient.util.AutismRotationUtil;
import autismclient.util.AutismRotationUtil.Rotation;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the pure rotation math the combat/aim modules rely on ({@link AutismRotationUtil}). These
 * methods operate on plain {@code Vec3} / {@code Rotation} values and need no Minecraft world, so they are
 * unit-testable.
 *
 * Not covered: {@code normalizeToSensitivity} / {@code sensitivityGcd}, which read
 * {@code Minecraft.getInstance().options} and would NPE without a running client.
 */
class RotationMathTest {

    private static final float EPS = 0.01f;

    // --- angleDifference: shortest signed angle, must wrap the 360 boundary ---

    @Test
    void testAngleDifferenceWrapsShortNotLong() {
        // Between 10 and 350 the short way is 20 degrees, NOT 340.
        assertEquals(20f, Math.abs(AutismRotationUtil.angleDifference(10f, 350f)), EPS,
            "angleDifference must wrap to the 20 short side, not 340");
    }

    @Test
    void testAngleDifferenceAntisymmetric() {
        // Reversing the arguments flips the sign (10->350 vs 350->10).
        assertEquals(AutismRotationUtil.angleDifference(10f, 350f),
            -AutismRotationUtil.angleDifference(350f, 10f), EPS,
            "angleDifference(a,b) should equal -angleDifference(b,a)");
    }

    @Test
    void testAngleDifferenceZero() {
        assertEquals(0f, AutismRotationUtil.angleDifference(45f, 45f), EPS, "same angle -> 0 difference");
    }

    // --- lookingAt: eye->target vector to yaw/pitch ---

    @Test
    void testLookAtStraightDown() {
        // Looking from above straight down at a point below -> pitch magnitude ~90 (vertical).
        Rotation r = AutismRotationUtil.lookingAt(new Vec3(0, 10, 0), new Vec3(0, 0, 0));
        assertEquals(90f, Math.abs(r.pitch()), 1.0f, "straight-down look should be ~90 degrees pitch");
    }

    @Test
    void testLookAtHorizontal() {
        // Looking straight ahead (horizontal) -> pitch ~0.
        Rotation r = AutismRotationUtil.lookingAt(new Vec3(0, 0, 0), new Vec3(0, 0, -10));
        assertEquals(0f, Math.abs(r.pitch()), 1.0f, "horizontal look should be ~0 degrees pitch");
    }

    // --- interpolate: fraction between two rotations ---

    @Test
    void testInterpolateMidpoint() {
        Rotation mid = AutismRotationUtil.interpolate(new Rotation(0f, 0f), new Rotation(90f, 0f), 0.5f);
        assertEquals(45f, mid.yaw(), 1.0f, "halfway between yaw 0 and 90 is ~45");
        assertEquals(0f, mid.pitch(), 1.0f, "pitch stays 0");
    }

    @Test
    void testInterpolateEndpoints() {
        Rotation a = new Rotation(10f, -20f);
        Rotation b = new Rotation(80f, 30f);
        Rotation at0 = AutismRotationUtil.interpolate(a, b, 0.0f);
        Rotation at1 = AutismRotationUtil.interpolate(a, b, 1.0f);
        assertEquals(a.yaw(), at0.yaw(), 1.0f, "t=0 stays at the start yaw");
        assertEquals(a.pitch(), at0.pitch(), 1.0f, "t=0 stays at the start pitch");
        assertEquals(b.yaw(), at1.yaw(), 1.0f, "t=1 reaches the end yaw");
        assertEquals(b.pitch(), at1.pitch(), 1.0f, "t=1 reaches the end pitch");
    }

    // AutismRotationUtil's lookingAt uses the convention up = +90, down = -90 (opposite sign of vanilla xRot);
    // apply() matches it, so it's internally consistent. These assert that documented convention.
    @Test
    void testLookAtStraightUp() {
        // Looking from below up to a point directly above -> pitch ~ +90 in this convention.
        Rotation r = AutismRotationUtil.lookingAt(new Vec3(0, 0, 0), new Vec3(0, 10, 0));
        assertEquals(90f, r.pitch(), 1.0f, "straight-up look is ~+90 pitch (AutismRotationUtil convention)");
    }

    @Test
    void testLookAtStraightDownSigned() {
        // Looking from above down to a point directly below -> pitch ~ -90 in this convention.
        Rotation r = AutismRotationUtil.lookingAt(new Vec3(0, 10, 0), new Vec3(0, 0, 0));
        assertEquals(-90f, r.pitch(), 1.0f, "straight-down look is ~-90 pitch (AutismRotationUtil convention)");
    }

    @Test
    void testInterpolateAtZero() {
        Rotation a = new Rotation(12f, -7f);
        Rotation b = new Rotation(88f, 33f);
        Rotation r = AutismRotationUtil.interpolate(a, b, 0.0f);
        assertEquals(a.yaw(), r.yaw(), EPS, "t=0 returns the first yaw exactly");
        assertEquals(a.pitch(), r.pitch(), EPS, "t=0 returns the first pitch exactly");
    }

    @Test
    void testInterpolateAtOne() {
        Rotation a = new Rotation(12f, -7f);
        Rotation b = new Rotation(88f, 33f);
        Rotation r = AutismRotationUtil.interpolate(a, b, 1.0f);
        assertEquals(b.yaw(), r.yaw(), EPS, "t=1 returns the second yaw exactly");
        assertEquals(b.pitch(), r.pitch(), EPS, "t=1 returns the second pitch exactly");
    }
}
