package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;

import com.boss.pvp.util.pvp.RotationManager;
import com.boss.pvp.util.input.HeldSlotManager;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismRotationUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SplashPotionItem;

import java.util.ArrayList;
import java.util.List;

public final class AutoPotModule extends Module {

    private enum Step { SWITCH_TO_POT, LOOK_DOWN, USE, RESTORE_ROT, SWITCH_BACK }

    private long lastThrowMs = 0L;

    private final List<Step> steps = new ArrayList<>();
    private int seqIndex = -1;
    private InteractionHand seqHand;
    private int seqPotSlot = -1;
    private int seqPrevSlot = -1;
    private float seqSavedYaw;
    private float seqSavedPitch;
    private boolean seqSilent = false;
    private boolean rotHold = false;

    public AutoPotModule() {
        super(BossPvpAddon.ID + ":autopot", "AutoPot", "Throws a splash heal potion when you get low.");

        add(new DoubleSetting("health", "Pot below health", 12.0, 1.0, 19.0, 0.5).group("General"));
        add(new BoolSetting("absorptionAware", "Count absorption (gapple) HP", false).group("General"));
        add(new IntSetting("delay", "Delay (ms)", 800, 100, 5000, 50).group("General"));
        add(new BoolSetting("rotate", "Look down to throw", true).group("General"));
        add(new DoubleSetting("throwPitch", "Throw pitch", 85.0, 70.0, 90.0, 1.0).group("General"));
        add(new BoolSetting("silentRotation", "Silent rotation", true).group("General"));
        add(new BoolSetting("mainhandOnly", "Main hand only", false).group("General"));
        add(new BoolSetting("switchToPot", "Pot even when not holding", true).group("Switch"));
        add(new BoolSetting("switchBack", "Switch back after", true).group("Switch"));
    }

    @Override
    public void onDisable() {

        Minecraft mc = Minecraft.getInstance();
        if (!seqSilent && rotHold && mc != null && mc.player != null) {
            applyRotation(mc.player, seqSavedYaw, seqSavedPitch);
        }

        if (mc != null && mc.player != null && seqPotSlot >= 0 && seqPrevSlot >= 0 && bool("switchBack")) {
            selectHotbarSlot(mc, mc.player, seqPrevSlot);
        }
        resetSequence();
        HeldSlotManager.clear(this);
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) { resetSequence(); HeldSlotManager.release(this); return; }

        boolean wantSlot = seqIndex >= 0 || wantsToThrow(p);
        if (!wantSlot) { HeldSlotManager.release(this); return; }
        HeldSlotManager.request(this, HeldSlotManager.PRIORITY_AUTOPOT);
        if (!HeldSlotManager.holds(this)) return;

        if (seqIndex < 0) {
            if (effectiveHealth(p) > (float) decimal("health")) return;

            long now = System.currentTimeMillis();
            if (now - lastThrowMs < integer("delay")) return;

            InteractionHand hand = findSplashHand(p);
            int potSlot = -1;
            if (hand == null) {
                if (!bool("switchToPot")) return;
                potSlot = findSplashHotbarSlot(p);
                if (potSlot < 0) return;
                hand = InteractionHand.MAIN_HAND;
            }
            startSequence(p, hand, potSlot);
            lastThrowMs = now;
            com.boss.pvp.util.CombatManager.pauseCombat(3);
        }

        runStep(mc, p);
    }

    private float effectiveHealth(LocalPlayer p) {
        return bool("absorptionAware") ? p.getHealth() + p.getAbsorptionAmount() : p.getHealth();
    }

    private boolean wantsToThrow(LocalPlayer p) {
        if (effectiveHealth(p) > (float) decimal("health")) return false;
        if (System.currentTimeMillis() - lastThrowMs < integer("delay")) return false;
        if (findSplashHand(p) != null) return true;
        return bool("switchToPot") && findSplashHotbarSlot(p) >= 0;
    }

    private void startSequence(LocalPlayer p, InteractionHand hand, int potSlot) {
        seqHand = hand;
        seqPotSlot = potSlot;
        seqPrevSlot = p.getInventory().getSelectedSlot();
        seqSavedYaw = p.getYRot();
        seqSavedPitch = p.getXRot();
        seqSilent = bool("silentRotation");
        rotHold = false;

        boolean doSwitch = potSlot >= 0;
        boolean doRotate = bool("rotate");

        steps.clear();
        if (doSwitch) steps.add(Step.SWITCH_TO_POT);
        if (doRotate) steps.add(Step.LOOK_DOWN);
        steps.add(Step.USE);
        if (doRotate) steps.add(Step.RESTORE_ROT);
        if (doSwitch && bool("switchBack")) steps.add(Step.SWITCH_BACK);

        seqIndex = 0;
    }

    private void runStep(Minecraft mc, LocalPlayer p) {
        if (seqIndex < 0 || seqIndex >= steps.size()) { resetSequence(); return; }

        Step step = steps.get(seqIndex);

        if (rotHold && step != Step.RESTORE_ROT) {
            submitLookDown(p);
        }

        switch (step) {
            case SWITCH_TO_POT -> selectHotbarSlot(mc, p, seqPotSlot);
            case LOOK_DOWN -> {
                rotHold = true;
                submitLookDown(p);
            }
            case USE -> {

                mc.gameMode.useItem(p, seqHand);
                p.swing(seqHand);
                AutismClientMessaging.sendPrefixed("§b[AutoPot] popped a pot at " + (int) p.getHealth() + "hp");
            }
            case RESTORE_ROT -> {
                rotHold = false;
                if (!seqSilent) {
                    applyRotation(p, seqSavedYaw, seqSavedPitch);
                }
            }
            case SWITCH_BACK -> selectHotbarSlot(mc, p, seqPrevSlot);
        }

        seqIndex++;
        if (seqIndex >= steps.size()) resetSequence();
    }

    private void submitLookDown(LocalPlayer p) {
        float yaw = p.getYRot();
        float pitch = (float) decimal("throwPitch");
        RotationManager.submit(yaw, pitch, RotationManager.PRIORITY_POT, seqSilent);
        if (!seqSilent) {
            applyRotation(p, yaw, pitch);
        }
    }

    private void resetSequence() {
        steps.clear();
        seqIndex = -1;
        seqHand = null;
        seqPotSlot = -1;
        seqPrevSlot = -1;
        seqSilent = false;
        rotHold = false;
    }

    private void applyRotation(LocalPlayer p, float yaw, float pitch) {
        AutismRotationUtil.Rotation current = AutismRotationUtil.playerRotation(p);
        AutismRotationUtil.Rotation desired = new AutismRotationUtil.Rotation(yaw, pitch);
        AutismRotationUtil.apply(p, AutismRotationUtil.normalizeToSensitivity(desired, current), false);
    }

    private void selectHotbarSlot(Minecraft mc, LocalPlayer p, int slot) {
        int clamped = Math.max(0, Math.min(8, slot));
        if (p.getInventory().getSelectedSlot() == clamped) return;
        p.getInventory().setSelectedSlot(clamped);
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(clamped));
        }
    }

    private int findSplashHotbarSlot(LocalPlayer p) {
        for (int i = 0; i <= 8; i++) {
            if (isSplashHeal(p.getInventory().getItem(i))) return i;
        }
        return -1;
    }

    private InteractionHand findSplashHand(LocalPlayer p) {
        if (isSplashHeal(p.getMainHandItem())) return InteractionHand.MAIN_HAND;
        if (!bool("mainhandOnly") && isSplashHeal(p.getOffhandItem())) return InteractionHand.OFF_HAND;
        return null;
    }

    private boolean isSplashHeal(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;

        return stack.getItem() instanceof SplashPotionItem || stack.is(Items.SPLASH_POTION);
    }
}
