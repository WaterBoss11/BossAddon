package com.boss.pvp.module.combat;

import com.boss.pvp.util.input.HeldSlotManager;
import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.boss.pvp.module.combat.SpecializedCombatDecisions.ChargeWindow;
import static com.boss.pvp.module.combat.SpecializedCombatDecisions.PushStep;

/** Also runnable via main with the decision helper and existing HeldSlotManager. No Minecraft bootstrap. */
public final class SpecializedCombatDecisionsTest {
    @Test void nativeChargeBoundaryIsInclusive() {
        check(SpecializedCombatDecisions.chargeWindow(19, 20, 60) == ChargeWindow.WINDUP, "windup ends at delay");
        check(SpecializedCombatDecisions.chargeWindow(20, 20, 60) == ChargeWindow.ACTIVE, "delay tick is active");
        check(SpecializedCombatDecisions.chargeWindow(60, 20, 60) == ChargeWindow.ACTIVE, "last native damage tick is inclusive");
        check(SpecializedCombatDecisions.chargeWindow(61, 20, 60) == ChargeWindow.SPENT, "restart only after damage window");
    }

    @Test void lungeAndAcknowledgementAreBounded() {
        check(SpecializedCombatDecisions.lungeSteps(0.8, 0.25, 18) == 4, "bounded outward and return budget");
        check(SpecializedCombatDecisions.lungeSteps(0.8, 0.25, 17) == 0, "insufficient charge budget");
        check(SpecializedCombatDecisions.lungeSteps(1.5, 0.1, 200) == 0, "too many movement steps");
        check(SpecializedCombatDecisions.lungeSteps(2.0, 0.25, 200) == 0, "distance hard ceiling");
        check(SpecializedCombatDecisions.lungeSteps(Double.NaN, 0.25, 200) == 0, "invalid movement distance");
        check(SpecializedCombatDecisions.lungeSteps(0.5, 0.5, 200) == 0, "speed hard ceiling");
        check(SpecializedCombatDecisions.sameHorizontalPosition(1.1, -2.0, 1.0, -2.0, 0.2), "near acknowledgement");
        check(!SpecializedCombatDecisions.sameHorizontalPosition(1.4, -2.0, 1.0, -2.0, 0.2), "reject unacknowledged displacement");
        check(!SpecializedCombatDecisions.sameHorizontalPosition(Double.NaN, 0, 0, 0, 0.2), "invalid server coordinate");
        check(!SpecializedCombatDecisions.sameHorizontalPosition(0, 0, 0, 0, -0.2), "invalid tolerance");
    }

    @Test void pistonNeverPowersBeforeConfirmation() {
        var sequence = new SpecializedCombatDecisions.PushSequence();
        sequence.confirmPiston(true, false);
        check(sequence.step() == PushStep.IDLE, "unsolicited piston update cannot start a plan");
        sequence.start();
        sequence.confirmPower();
        check(sequence.step() == PushStep.PISTON, "power update cannot skip piston");
        sequence.sent();
        sequence.confirmPiston(false, false);
        check(sequence.step() == PushStep.PISTON_ACK, "wrong orientation cannot acknowledge piston");
        sequence.confirmPiston(true, false);
        check(sequence.step() == PushStep.POWER, "server piston acknowledgement permits power");
        sequence.confirmPower();
        check(sequence.step() == PushStep.POWER, "unsent power cannot acknowledge itself");
        sequence.sent();
        sequence.confirmPiston(true, true);
        check(sequence.step() == PushStep.POWER_ACK, "extension alone is not both acknowledgements");
        sequence.confirmPower();
        check(sequence.step() == PushStep.COMPLETE, "out-of-order extension and power are both accounted for");
        sequence.reset();
        sequence.start();
        sequence.sent();
        sequence.confirmPiston(true, false);
        sequence.sent();
        sequence.confirmPower();
        check(sequence.step() == PushStep.EXTENSION, "no stale extension after reset");
        sequence.confirmPiston(true, true);
        check(sequence.step() == PushStep.COMPLETE, "ordered power then extension");
    }

    @Test void pistonRetryAndWaitBudgetsDoNotResetForever() {
        var sequence = new SpecializedCombatDecisions.PushSequence();
        sequence.start();
        check(!sequence.retryRejected(2), "cannot retry an unsent action");
        sequence.sent();
        check(sequence.tick(3), "first bounded wait");
        check(sequence.retryRejected(1), "one explicitly rejected retry");
        check(sequence.retries() == 1 && sequence.age() == 0, "retry count and stage clock");
        sequence.sent();
        check(!sequence.retryRejected(1), "retry ceiling");
        check(sequence.tick(3) && sequence.tick(3) && !sequence.tick(3), "total age survives transitions and retries");
        sequence.reset();
        sequence.start();
        sequence.sent();
        sequence.confirmPiston(true, false);
        sequence.sent();
        sequence.confirmPiston(true, true);
        check(sequence.retryRejected(1), "retry rejected power");
        sequence.sent();
        sequence.confirmPower();
        check(sequence.step() == PushStep.EXTENSION, "rejected power clears stale extension evidence");
    }

    @Test void cityProtectsBodySurroundFloorAndNegativeCoordinates() {
        check(protectedAt(0, 64, 0), "own or friend body");
        check(protectedAt(1, 64, 0) && protectedAt(1, 65, 1), "own or friend surround / upper corners");
        check(protectedAt(0, 63, 0), "own or friend floor");
        check(!protectedAt(1, 63, 0) && !protectedAt(0, 62, 0), "do not confuse neighbouring underground cells with floor");
        check(!protectedAt(2, 64, 0) && !protectedAt(0, 66, 0), "protection is bounded");
        check(SpecializedCombatDecisions.protectedCell(-3, 64, -1, -1.3, 64, -0.3, -0.7, 65.8, 0.3),
            "negative-coordinate, boundary-straddling player surround");
        check(!SpecializedCombatDecisions.protectedCell(-4, 64, -1, -1.3, 64, -0.3, -0.7, 65.8, 0.3),
            "negative-coordinate outer bound");
    }

    @Test void autoClutchCanPreemptEverySpecializedLease() {
        Object specialized = new Object(), clutch = new Object();
        HeldSlotManager.reset();
        try {
            check(HeldSlotManager.tryAcquire(specialized, SpecializedCombatDecisions.PREEMPTIBLE_SLOT_PRIORITY), "acquire native lease");
            HeldSlotManager.request(clutch, HeldSlotManager.PRIORITY_AUTOCLUTCH);
            HeldSlotManager.endTick();
            check(!HeldSlotManager.holds(specialized), "specialized module must unwind for clutch");
            HeldSlotManager.request(clutch, HeldSlotManager.PRIORITY_AUTOCLUTCH);
            HeldSlotManager.endTick();
            check(HeldSlotManager.holds(clutch), "clutch receives next lease");
            check(!HeldSlotManager.tryAcquire(specialized, SpecializedCombatDecisions.PREEMPTIBLE_SLOT_PRIORITY), "do not steal clutch slot");
        } finally { HeldSlotManager.reset(); }
    }

    @Test void ownedSourcesParseWithoutCompilingTheProject() throws Exception { parseOwnedSources(Path.of(".")); }

    private static boolean protectedAt(int x, int y, int z) {
        return SpecializedCombatDecisions.protectedCell(x, y, z, 0.2, 64, 0.2, 0.8, 65.8, 0.8);
    }

    private static void parseOwnedSources(Path project) throws Exception {
        Path main = project.resolve("src").resolve("main").resolve("java").resolve("com").resolve("boss").resolve("pvp");
        Path combat = main.resolve("module").resolve("combat");
        Path mixin = main.resolve("client").resolve("mixin");
        List<Path> sources = List.of(combat.resolve("SpearAssistModule.java"), combat.resolve("PistonPushModule.java"),
            combat.resolve("CityMineModule.java"), combat.resolve("SpecializedCombatDecisions.java"),
            mixin.resolve("BossSpearAssistMovementMixin.java"), mixin.resolve("BossSpecializedCombatPacketMixin.java"),
            mixin.resolve("BossCityMineGameModeAccess.java"), mixin.resolve("BossCityMineInputMixin.java"));
        var compiler = ToolProvider.getSystemJavaCompiler();
        check(compiler != null, "JDK required");
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            var task = (JavacTask) compiler.getTask(null, files, diagnostics, List.of("-proc:none"), null,
                files.getJavaFileObjectsFromPaths(sources));
            task.parse().forEach(ignored -> { });
        }
        for (var diagnostic : diagnostics.getDiagnostics()) {
            check(diagnostic.getKind() != Diagnostic.Kind.ERROR, diagnostic.toString());
        }
        String spear = Files.readString(sources.get(0));
        String piston = Files.readString(sources.get(1));
        String city = Files.readString(sources.get(2));
        check(!spear.contains("setDeltaMovement("), "lunge must not overwrite or zero natural velocity");
        check(!city.contains("ServerboundPlayerActionPacket") && !city.contains("setDown("), "normal mining, no packet/key engine");
        for (String source : List.of(spear, piston, city)) {
            check(!source.contains("autismclient.util.") && !source.contains("RotationManager.reset("), "no internal helper / shared-state reset");
            check(!source.contains(".setBlock(") && !source.contains(".setBlockAndUpdate("), "no fake local blocks");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        var tests = new SpecializedCombatDecisionsTest();
        tests.nativeChargeBoundaryIsInclusive();
        tests.lungeAndAcknowledgementAreBounded();
        tests.pistonNeverPowersBeforeConfirmation();
        tests.pistonRetryAndWaitBudgetsDoNotResetForever();
        tests.cityProtectsBodySurroundFloorAndNegativeCoordinates();
        tests.autoClutchCanPreemptEverySpecializedLease();
        parseOwnedSources(args.length == 0 ? Path.of(".") : Path.of(args[0]));
        System.out.println("SpecializedCombatDecisionsTest: 7 checks passed (headless; no Gradle, game or donor execution)");
    }
}
