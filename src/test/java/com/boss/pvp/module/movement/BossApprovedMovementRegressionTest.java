package com.boss.pvp.module.movement;

import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.classfile.instruction.InvokeInstruction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BossApprovedMovementRegressionTest {
    private static final double EPS = 1.0E-9;

    @Test
    void webReductionIsBoundedAndPreservesWeaving() {
        assertEquals(0.75, FastWebModule.retention(0.25, 0.75), EPS);
        assertEquals(0.5, FastWebModule.retention(0.5, 0.25), EPS);
        assertEquals(1.0, FastWebModule.retention(0.25, 100.0), EPS);
        assertEquals(0.25, FastWebModule.retention(0.25, Double.NaN), EPS);
    }

    @Test
    void webEscapeNeverCancelsAJumpOrArrestsAnExistingFall() {
        assertEquals(-0.3, FastWebModule.escapeY(0.0, 0.3), EPS);
        assertEquals(-0.5, FastWebModule.escapeY(-0.1, 100.0), EPS);
        assertEquals(-0.8, FastWebModule.escapeY(-0.8, 0.3), EPS);
        assertEquals(0.42, FastWebModule.escapeY(0.42, 0.3), EPS);
        assertEquals(-0.1, FastWebModule.escapeY(-0.1, Double.NaN), EPS);
    }

    @Test
    void ladderAscentIsBoundedWithoutClampingUnrelatedMomentum() {
        assertEquals(0.2872, FastLadderModule.ascentY(0.0, 0.2872), EPS);
        assertEquals(0.5, FastLadderModule.ascentY(0.0, 100.0), EPS);
        assertEquals(0.2, FastLadderModule.ascentY(0.0, -1.0), EPS);
        assertEquals(0.8, FastLadderModule.ascentY(0.8, 0.3), EPS);
        assertEquals(-0.1, FastLadderModule.ascentY(-0.1, Double.NaN), EPS);
    }

    @Test
    void waterOnlySupportsFeetAtOrAboveTheSurfaceAndSmallFalls() {
        double surface = 64.0 + 8.0 / 9.0;
        assertTrue(WaterWalkModule.canSupport(surface, surface, -0.08, 0.0));
        assertTrue(WaterWalkModule.canSupport(surface + 0.2, surface, -0.5, 3.0));
        assertFalse(WaterWalkModule.canSupport(surface - 0.01, surface, 0.0, 0.0));
        assertFalse(WaterWalkModule.canSupport(surface + 1.0, surface, -0.5001, 2.0));
        assertFalse(WaterWalkModule.canSupport(surface + 1.0, surface, -0.2, 3.01));
        assertFalse(WaterWalkModule.canSupport(surface, surface, 0.0, -1.0));
        assertFalse(WaterWalkModule.canSupport(Double.NaN, surface, 0.0, 0.0));
        assertFalse(WaterWalkModule.canSupport(surface, surface, Double.POSITIVE_INFINITY, 0.0));
    }

    @Test
    void riptideScalesOnlyTheImpulseAndCapsGeneratedSpeed() {
        Vec3 boost = TridentBoostModule.scaleImpulse(new Vec3(3.0, 0.0, 0.0), 1.25, 1.0);
        assertEquals(3.75, boost.x, EPS);
        assertEquals(0.0, boost.y, EPS);
        Vec3 capped = TridentBoostModule.scaleImpulse(new Vec3(3.0, 4.0, 0.0), 100.0, 100.0);
        assertEquals(6.0, capped.length(), EPS);
        assertTrue(capped.x >= 3.0 && capped.y >= 4.0);
        Vec3 vertical = TridentBoostModule.scaleImpulse(new Vec3(0.0, 3.0, 0.0), 2.0, 1.0);
        assertEquals(3.0, vertical.y, EPS);
    }

    @Test
    void riptideLeavesUnboostableImpulsesAndInvalidSettingsAlone() {
        Vec3 excessive = new Vec3(7.0, 0.0, 0.0);
        assertSame(excessive, TridentBoostModule.scaleImpulse(excessive, 2.0, 2.0));
        assertSame(Vec3.ZERO, TridentBoostModule.scaleImpulse(Vec3.ZERO, 2.0, 2.0));
        Vec3 normal = new Vec3(1.0, -1.0, 1.0);
        Vec3 unchanged = TridentBoostModule.scaleImpulse(normal, Double.NaN, Double.POSITIVE_INFINITY);
        assertEquals(normal, unchanged);
    }

    @Test
    void native26_2HooksExistAndInvocationTargetsAreUnambiguous() throws IOException {
        MethodModel web = nativeMethod("net/minecraft/world/level/block/WebBlock", "entityInside",
            "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/InsideBlockEffectApplier;Z)V");
        assertEquals(1L, calls(web, "net/minecraft/world/entity/Entity", "makeStuckInBlock",
            "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/Vec3;)V"));
        nativeMethod("net/minecraft/world/entity/LivingEntity", "handleOnClimbable",
            "(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;");
        nativeMethod("net/minecraft/world/level/block/LiquidBlock", "getCollisionShape",
            "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;");
        MethodModel trident = nativeMethod("net/minecraft/world/item/TridentItem", "releaseUsing",
            "(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)Z");
        assertEquals(1L, calls(trident, "net/minecraft/world/entity/player/Player", "push", "(DDD)V"));
        assertEquals(1L, calls(trident, "net/minecraft/world/entity/player/Player", "isInWaterOrRain", "()Z"));
        MethodModel use = nativeMethod("net/minecraft/world/item/TridentItem", "use",
            "(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;");
        assertEquals(1L, calls(use, "net/minecraft/world/entity/player/Player", "isInWaterOrRain", "()Z"));
    }

    private static MethodModel nativeMethod(String owner, String name, String descriptor) throws IOException {
        try (var in = BossApprovedMovementRegressionTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(in, owner);
            return ClassFile.of().parse(in.readAllBytes()).methods().stream()
                .filter(m -> m.methodName().equalsString(name) && m.methodType().equalsString(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError(owner + "." + name + descriptor));
        }
    }

    private static long calls(MethodModel method, String owner, String name, String descriptor) {
        return method.code().orElseThrow().elementStream().filter(element -> element instanceof InvokeInstruction invoke
            && invoke.owner().asInternalName().equals(owner) && invoke.name().equalsString(name)
            && invoke.type().equalsString(descriptor)).count();
    }
}
