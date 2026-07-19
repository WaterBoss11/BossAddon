package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.RotationManager;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.MenuMode;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismInventoryHelper;
import autismclient.util.AutismRotationUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;

public final class AutoHookModule extends Module {

    private enum Step { SWITCH_ROD, CAST, REEL, SWITCH_BACK }

    private static final long REEL_DELAY_MS = 250L;

    private float lastHealth = -1.0f;
    private final Deque<Long> hits = new ArrayDeque<>();

    private int seq = -1;
    private int prevSlot = -1;
    private long lastCastMs = 0L;
    private long castedAtMs = 0L;
    private LivingEntity victim;

    public AutoHookModule() {
        super(BossPvpAddon.ID + ":autohook", "AutoHook", "Casts a fishing rod at attackers.");

        add(RegistryListSetting.entityTypes("entities", "Targets", "minecraft:player"));
        add(new IntSetting("hits", "Hits to trigger", 3, 1, 10, 1).group("Trigger"));
        add(new IntSetting("window", "Combo window (ms)", 1500, 100, 5000, 50).group("Trigger").visibleWhen(MenuMode::advanced));
        add(new IntSetting("cooldown", "Cooldown (ms)", 1000, 0, 5000, 50).group("Trigger").visibleWhen(MenuMode::advanced));
        add(new ChoiceSetting("triggerMode", "Trigger mode", "Combo", "Combo", "On-attack").group("Trigger").visibleWhen(MenuMode::advanced));

        add(new DoubleSetting("range", "Range", 4.0, 1.0, 6.0, 0.1).group("Targeting")
            .description("How close the attacker must be. The default matches normal reach; higher may be flagged by anticheat."));
        add(new ChoiceSetting("rotationMode", "Rotation", "Silent", "Silent", "Real", "None").group("Targeting").visibleWhen(MenuMode::advanced));
        add(new ChoiceSetting("targetPriority", "Target priority", "Closest", "Closest", "Lowest HP").group("Targeting").visibleWhen(MenuMode::advanced));
        add(new BoolSetting("raytrace", "Only targets you can see", true).group("Targeting").visibleWhen(MenuMode::advanced));

        add(new BoolSetting("reelAfterCast", "Reel after cast", true).group("Actions").visibleWhen(MenuMode::advanced));
        add(new BoolSetting("pullMode", "Pull (reel immediately)", false).group("Actions").visibleWhen(MenuMode::advanced));
        add(new BoolSetting("switchBack", "Switch back to previous item", true).group("Actions").visibleWhen(MenuMode::advanced));
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            LocalPlayer pl = mc.player;

            if (mc.gameMode != null && pl.fishing != null && pl.getMainHandItem().is(Items.FISHING_ROD)) {
                mc.gameMode.useItem(pl, InteractionHand.MAIN_HAND);
            }

            if (prevSlot >= 0 && bool("switchBack")) {
                AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            }
        }
        seq = -1; prevSlot = -1; victim = null;
        lastHealth = -1.0f;
        hits.clear();
        HeldSlotManager.clear(this);
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null || mc.gameMode == null || mc.gui.screen() != null) return;

        if (!HeldSlotManager.holds(this) && prevSlot >= 0) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            seq = -1; prevSlot = -1; victim = null;
            return;
        }

        long now = System.currentTimeMillis();
        trackCombo(p, now);

        if (seq < 0) {
            if (now - lastCastMs < PvpUtil.jitterMs(integer("cooldown"))) return;
            if (!shouldTrigger(mc, p, now)) return;
            LivingEntity target = nearestEnemy(mc, p);
            if (target == null) return;
            if (findRod(p) < 0) return;

            HeldSlotManager.request(this, HeldSlotManager.PRIORITY_AUTOHOOK);
            if (!HeldSlotManager.holds(this)) return;
            victim = target;
            prevSlot = p.getInventory().getSelectedSlot();
            seq = 0;
            lastCastMs = now;
            hits.clear();
        } else {
            HeldSlotManager.request(this, HeldSlotManager.PRIORITY_AUTOHOOK);
            if (!HeldSlotManager.holds(this)) return;
        }

        Step[] steps = Step.values();
        if (seq >= steps.length) { finish(); return; }
        switch (steps[seq]) {
            case SWITCH_ROD -> {
                int rod = findRod(p);
                if (rod >= 0) AutismInventoryHelper.selectHotbarSlot(mc, rod);
            }
            case CAST -> {
                if (victim != null && victim.isAlive()) {
                    submitRotation(p, victim.getBoundingBox().getCenter());
                }
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                p.swing(InteractionHand.MAIN_HAND);
                castedAtMs = now;
            }
            case REEL -> {
                if (!bool("reelAfterCast")) break;
                if (now - castedAtMs < (bool("pullMode") ? 50L : REEL_DELAY_MS)) return;
                mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
                p.swing(InteractionHand.MAIN_HAND);
            }
            case SWITCH_BACK -> {
                if (bool("switchBack") && prevSlot >= 0) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
                victim = null;
            }
        }
        seq++;
        if (seq >= steps.length) finish();
    }

    private void finish() {
        seq = -1; prevSlot = -1; victim = null;
        HeldSlotManager.release(this);
    }

    private void trackCombo(LocalPlayer p, long now) {
        float hp = p.getHealth() + p.getAbsorptionAmount();
        if (lastHealth >= 0.0f && hp < lastHealth - 0.01f) hits.addLast(now);
        lastHealth = hp;
        prune(now);
    }

    private boolean comboTriggered(long now) {
        prune(now);
        return hits.size() >= integer("hits");
    }

    private boolean shouldTrigger(Minecraft mc, LocalPlayer p, long now) {
        if ("On-attack".equals(choice("triggerMode"))) {
            if (mc.options != null && mc.options.keyAttack.isDown()) return true;
            return BossPvpAddon.killAura != null && BossPvpAddon.killAura.isEnabled()
                && BossPvpAddon.killAura.currentTarget() != null;
        }
        return comboTriggered(now);
    }

    private void prune(long now) {
        long cutoff = now - integer("window");
        while (!hits.isEmpty() && hits.peekFirst() < cutoff) hits.pollFirst();
    }

    private LivingEntity nearestEnemy(Minecraft mc, LocalPlayer p) {
        Set<String> ids = PvpUtil.entityIds(list("entities"));
        String sort = "Lowest HP".equals(choice("targetPriority")) ? "Health" : "Distance";
        return PvpUtil.findTarget(mc, p, decimal("range"), 180.0, ids, bool("raytrace"), sort);
    }

    private int findRod(LocalPlayer p) {
        for (int i = 0; i <= 8; i++) {
            if (p.getInventory().getItem(i).is(Items.FISHING_ROD)) return i;
        }
        return -1;
    }

    private void submitRotation(LocalPlayer p, Vec3 point) {
        String mode = choice("rotationMode");
        if ("None".equals(mode)) return;
        boolean silent = !"Real".equals(mode);
        AutismRotationUtil.Rotation wanted = AutismRotationUtil.lookingAt(point, p.getEyePosition());
        RotationManager.submit(wanted.yaw(), wanted.pitch(), RotationManager.PRIORITY_PLACE, silent);
        if (!silent) {
            AutismRotationUtil.Rotation cur = AutismRotationUtil.playerRotation(p);
            AutismRotationUtil.apply(p, AutismRotationUtil.normalizeToSensitivity(wanted, cur), false);
        }
    }
}
