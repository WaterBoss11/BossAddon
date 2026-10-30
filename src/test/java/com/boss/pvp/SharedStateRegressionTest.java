package com.boss.pvp;

import com.boss.pvp.util.InputUtil;
import com.boss.pvp.util.InventoryUtil;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.PvpUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SharedStateRegressionTest {
    @BeforeEach
    @AfterEach
    void clearLease() {
        HeldSlotManager.reset();
    }

    @Test
    void hotbarConversionsRejectArmorAndOffhand() {
        assertFalse(InventoryUtil.validHotbarSlot(-1));
        assertTrue(InventoryUtil.validHotbarSlot(0));
        assertTrue(InventoryUtil.validHotbarSlot(8));
        assertFalse(InventoryUtil.validHotbarSlot(9));
        assertEquals(-1, InventoryUtil.hotbarButton(35));
        assertEquals(0, InventoryUtil.hotbarButton(36));
        assertEquals(8, InventoryUtil.hotbarButton(44));
        assertEquals(-1, InventoryUtil.hotbarButton(45));
    }

    @Test
    void refillThresholdIsInclusiveAndWorksForSmallStacks() {
        assertTrue(InventoryUtil.shouldRefill(16, 64, 25));
        assertFalse(InventoryUtil.shouldRefill(17, 64, 25));
        assertTrue(InventoryUtil.shouldRefill(4, 16, 25));
        assertFalse(InventoryUtil.shouldRefill(5, 16, 25));
        assertFalse(InventoryUtil.shouldRefill(1, 1, 100));
        assertFalse(InventoryUtil.shouldRefill(0, 64, 25));
        assertFalse(InventoryUtil.shouldRefill(64, 64, 100));
        assertFalse(InventoryUtil.shouldRefill(1, 64, 0));
        assertFalse(InventoryUtil.shouldRefill(1, 64, 101));
        assertTrue(InventoryUtil.shouldRefill(1, Integer.MAX_VALUE, 1));
    }

    @Test
    void mouseBindsUseHostEncodingAndNeverAliasUnboundOrPrimaryAttack() {
        assertEquals(1, InputUtil.mouseButton(-1001));
        assertEquals(2, InputUtil.mouseButton(-1002));
        assertEquals(-1, InputUtil.mouseButton(-1000));
        assertEquals(-1, InputUtil.mouseButton(-1));
        assertEquals(-1, InputUtil.mouseButton(65));
        assertEquals(-1, InputUtil.mouseButton(Integer.MIN_VALUE));
    }

    @Test
    void crosshairRingUsesTheActualOverlayTransform() {
        var pose = new org.joml.Matrix3x2f().translate(10.0f, 20.0f).scale(0.25f);
        var center = com.boss.pvp.client.hud.FovCircleHud.centerForPose(pose, 320, 180);
        var projected = pose.transformPosition(new org.joml.Vector2f(center));
        assertEquals(160.0f, projected.x, 0.001f);
        assertEquals(90.0f, projected.y, 0.001f);
    }

    @Test
    void migratedFriendsAreMergedWithoutCaseDuplicatesOrLoss() {
        assertEquals(List.of("Alex", "steve", "Casey"),
            PvpUtil.mergeFriendNames(" Alex |steve|", "STEVE|Casey||"));
        assertEquals(List.of(), PvpUtil.mergeFriendNames(null, " | "));
        assertEquals(List.of("OnlyLegacy"), PvpUtil.mergeFriendNames(null, "OnlyLegacy"));
    }

    @Test
    void nativeTeamRulesProtectSingleMatchingHelmetsAndNameColors() {
        assertTrue(PvpUtil.autoTeamsMatch(true, null, null, null, null));
        assertTrue(PvpUtil.autoTeamsMatch(false, null, null, 0x123456, 0x123456));
        assertTrue(PvpUtil.autoTeamsMatch(false, 0x55AAFF, 0x55AAFF, null, null));
        assertFalse(PvpUtil.autoTeamsMatch(false, null, null, null, null));
        assertFalse(PvpUtil.autoTeamsMatch(false, 1, 2, 3, 4));
        assertFalse(PvpUtil.autoTeamsMatch(false, null, 1, null, 2));
    }

    @Test
    void sharedAttackPolicyKeepsFriendsProtectedEvenWhenAutomaticTeamDetectionIsOff() {
        assertTrue(com.boss.pvp.util.CombatManager.shouldCancelAttack(true, false, true, false));
        assertTrue(com.boss.pvp.util.CombatManager.shouldCancelAttack(true, true, false, false));
        assertFalse(com.boss.pvp.util.CombatManager.shouldCancelAttack(true, true, false, true));
        assertFalse(com.boss.pvp.util.CombatManager.shouldCancelAttack(false, true, true, false));
        assertFalse(com.boss.pvp.util.CombatManager.shouldCancelAttack(true, false, false, false));
    }

    @Test
    void emergencyPreemptionRestoresBeforeTheSuccessorCanSnapshotASlot() {
        int[] selected = {4};
        class Borrower implements HeldSlotManager.Revocable {
            boolean restored;
            public void onLeaseRevoked() {
                assertTrue(HeldSlotManager.holds(this), "restore must run before the lease is cleared");
                selected[0] = 0;
                restored = true;
                HeldSlotManager.clear(this);
            }
        }
        Borrower borrower = new Borrower();
        Object healing = new Object();
        assertTrue(HeldSlotManager.tryAcquire(borrower, 20));
        HeldSlotManager.request(healing, HeldSlotManager.PRIORITY_AUTOPOT);
        HeldSlotManager.endTick();
        assertTrue(borrower.restored);
        assertEquals(0, selected[0]);
        assertFalse(HeldSlotManager.holds(healing));
        assertFalse(HeldSlotManager.tryAcquire(borrower, 20), "unwind gap cannot be bypassed by immediate acquisition");
        assertFalse(HeldSlotManager.tryAcquire(new Object(), 30), "pending heal reservation must survive the unwind");
        HeldSlotManager.endTick();
        assertTrue(HeldSlotManager.holds(healing));
        assertEquals(0, selected[0], "healing observes the original, not the borrowed pickaxe/rod slot");
    }

    @Test
    void autoClutchCanPreemptADeferredWeaponWithoutLosingTheOriginalSlot() {
        boolean[] restored = {false};
        HeldSlotManager.Revocable weapon = () -> restored[0] = true;
        Object clutch = new Object();
        assertTrue(HeldSlotManager.tryAcquire(weapon, HeldSlotManager.PRIORITY_AUTOWEAPON));
        HeldSlotManager.request(clutch, HeldSlotManager.PRIORITY_AUTOCLUTCH);
        HeldSlotManager.endTick();
        assertTrue(restored[0]);
        assertFalse(HeldSlotManager.holds(weapon));
        assertFalse(HeldSlotManager.tryAcquire(weapon, HeldSlotManager.PRIORITY_AUTOWEAPON));
        HeldSlotManager.endTick();
        assertTrue(HeldSlotManager.holds(clutch));
    }

    @Test
    void expiredLeasesUnwindBeforeReleaseToo() {
        boolean[] restored = {false};
        HeldSlotManager.Revocable owner = () -> restored[0] = true;
        assertTrue(HeldSlotManager.tryAcquire(owner, 20));
        for (int i = 0; i < 7; i++) HeldSlotManager.endTick();
        assertTrue(restored[0]);
        assertFalse(HeldSlotManager.holds(owner));
    }

    @Test
    void immediateLeaseCannotStealFromAnOwnerOrHigherPriorityRequest() {
        Object healing = new Object();
        Object action = new Object();
        assertFalse(HeldSlotManager.holds(null));
        assertFalse(HeldSlotManager.tryAcquire(null, 100));
        HeldSlotManager.request(healing, 100);
        assertFalse(HeldSlotManager.tryAcquire(action, 30));
        HeldSlotManager.endTick();
        assertTrue(HeldSlotManager.holds(healing));
        assertFalse(HeldSlotManager.tryAcquire(action, 30));
        HeldSlotManager.clear(action);
        HeldSlotManager.clear(healing);
        assertTrue(HeldSlotManager.tryAcquire(action, 30));
        HeldSlotManager.clear(action);
        assertFalse(HeldSlotManager.holds(action));
    }
}
