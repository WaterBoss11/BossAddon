package com.boss.pvp.module.movement;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.InvokeInstruction;

final class CoreMovementLogicTest {
    @Test
    void boundedMovementDecisions() {
        logicChecks();
    }

    @Test
    void exact26_2AttackHook() throws IOException {
        nativeHookCheck();
    }

    public static void main(String[] args) throws IOException {
        logicChecks();
        nativeHookCheck();
        System.out.println("Core movement checks passed (math boundaries and exact native attack hook).");
    }

    private static void logicChecks() {
        var toward = CoreMovementSupport.toward(3, 4, 0.2);
        near(0.2, Math.hypot(toward.x(), toward.z()), "bounded diagonal approach");
        var close = CoreMovementSupport.toward(0.02, -0.01, 0.2);
        near(0.02, close.x(), "no X overshoot");
        near(-0.01, close.z(), "no Z overshoot");
        near(0, CoreMovementSupport.toward(0, 0, 0.3).x(), "coincident hole");
        near(0, CoreMovementSupport.toward(Double.NaN, 1, 0.3).z(), "invalid position");
        near(0, CoreMovementSupport.toward(1, 1, -0.3).x(), "invalid speed");

        var clockwise = CoreMovementSupport.orbit(3, 0, 3, 0.25, 1);
        var counter = CoreMovementSupport.orbit(3, 0, 3, 0.25, -1);
        near(0, clockwise.x(), "tangent at orbit radius");
        near(0.25, clockwise.z(), "clockwise tangent");
        near(-0.25, counter.z(), "manual direction reversal");
        check(CoreMovementSupport.orbit(5, 0, 3, 0.25, 1).x() > 0, "outside orbit steers inward");
        check(CoreMovementSupport.orbit(1, 0, 3, 0.25, 1).x() < 0, "inside orbit steers outward");
        near(0, CoreMovementSupport.orbit(0, 0, 3, 0.25, 1).z(), "coincident target is safe");
        for (int side : new int[] {-1, 1}) {
            for (double distance : new double[] {0.01, 1, 3, 10}) {
                var orbit = CoreMovementSupport.orbit(distance, distance, 3, 0.27, side);
                check(Math.hypot(orbit.x(), orbit.z()) <= 0.27 + 1.0e-10, "orbit never adds speed");
            }
        }

        check(CoreMovementSupport.arrived(0.03, 0.1, -0.02), "hole arrival uses tolerance, not float equality");
        check(!CoreMovementSupport.arrived(0.061, 0, 0), "outside horizontal arrival tolerance");
        check(!CoreMovementSupport.arrived(0, 0.16, 0), "above hole is not a completed snap");
        check(!CoreMovementSupport.arrived(Double.NaN, 0, 0), "invalid hole arrival rejected");
        near(1.5, CoreMovementSupport.dropBudget(1.5, 100, 100), "initial fall budget");
        near(1.0, CoreMovementSupport.dropBudget(1.5, 100, 99.5), "fall budget shrinks");
        near(0, CoreMovementSupport.dropBudget(1.5, 100, 98.5), "budget cannot extend on repeated ticks");
        check(CoreMovementSupport.dropBudget(1.5, 100, 98) < 0, "deep fall rejected");
        check(Double.isNaN(CoreMovementSupport.dropBudget(1, Double.NaN, 1)), "invalid fall anchor rejected");
        near(-0.6, CoreMovementSupport.descent(-0.08, 1, 0.6), "bounded descent acceleration");
        near(-0.2, CoreMovementSupport.descent(-0.08, 0.2, 0.6), "descent stops at verified ground");
        near(0.42, CoreMovementSupport.descent(0.42, 1, 0.6), "deliberate jump is never reversed");
        near(-0.08, CoreMovementSupport.descent(-0.08, Double.NaN, 0.6), "unknown ground is not accelerated");
        near(-0.08, CoreMovementSupport.descent(-0.08, 0, 0.6), "no descent through ground");

        near(181, CoreMovementSupport.turn(179, -179, 5), "shortest yaw arc across wrap");
        near(-181, CoreMovementSupport.turn(-179, 179, 5), "opposite yaw wrap");
        near(12, CoreMovementSupport.turn(0, 90, 12), "native steering is turn-limited");
        near(5, CoreMovementSupport.turn(5, Float.NaN, 12), "invalid desired rotation ignored");
        check(!CoreMovementSupport.needsSeparation(40, 6, 1, 6), "actual target distance, not normalized-vector length");
        check(CoreMovementSupport.needsSeparation(10, 6, 1, 6), "closing speed starts braking early");
        check(!CoreMovementSupport.needsSeparation(10, 6, -1, 6), "opening target does not count as closing");
        check(CoreMovementSupport.needsSeparation(6, 6, 0, 6), "stand-off boundary is inclusive");
        check(CoreMovementSupport.needsSeparation(Double.NaN, 6, 1, 6), "invalid separation fails safe");
    }

    private static void nativeHookCheck() throws IOException {
        String descriptor = "(Lnet/minecraft/world/entity/Entity;FLnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/damagesource/DamageSource;FZ)V";
        try (var stream = CoreMovementLogicTest.class.getResourceAsStream("/net/minecraft/world/entity/player/Player.class")) {
            check(stream != null, "cached Minecraft 26.2 class must be on the test classpath");
            var model = ClassFile.of().parse(stream.readAllBytes());
            var hook = model.methods().stream().filter(method -> method.methodName().equalsString("causeExtraKnockback")
                && method.methodType().equalsString(descriptor)).findFirst().orElseThrow(
                    () -> new AssertionError("26.2 attack hook descriptor changed"));
            var elements = hook.code().orElseThrow().elementList();
            long constants = elements.stream().filter(element -> element instanceof ConstantInstruction constant
                && constant.constantValue() instanceof Double value && value == 0.6).count();
            long sprintCalls = elements.stream().filter(element -> element instanceof InvokeInstruction call
                && call.owner().asInternalName().equals("net/minecraft/world/entity/player/Player")
                && call.name().equalsString("setSprinting") && call.type().equalsString("(Z)V")).count();
            check(constants == 2, "hook must contain exactly the X/Z 0.6 attack multipliers");
            check(sprintCalls == 1, "hook must contain exactly one native sprint cancellation");
        }
    }

    private static void near(double expected, double actual, String message) {
        check(Double.isFinite(actual) && Math.abs(expected - actual) < 1.0e-6, message + ": " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
