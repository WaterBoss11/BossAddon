package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.input.HeldSlotManager;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismInventoryHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public final class ShieldBreakerModule extends Module {

    private enum Step { SWITCH_AXE, HIT, SWITCH_BACK }

    private long lastMs = 0L;
    private int seq = -1;
    private int prevSlot = -1;
    private LivingEntity victim;

    public ShieldBreakerModule() {
        super(BossPvpAddon.ID + ":shieldbreaker", "ShieldBreaker", "Automatically switches to an axe to disable a blocking enemy's shield, then switches back.");

        add(new DoubleSetting("range", "Range", 3.0, 1.0, 3.5, 0.1)
            .description("Attack reach. Vanilla is 3.0; higher may flag on anticheat."));
        add(new BoolSetting("onlyWhenBlocking", "Only when target is blocking", true).group("General"));
        add(new BoolSetting("teamCheck", "Ignore teammates", false)
            .description("Skip players wearing leather armour dyed the same colour as yours (teammates).").group("Team"));
        add(new BoolSetting("switchBack", "Switch back after", true).group("General"));
        add(new IntSetting("delay", "Delay (ms)", 250, 0, 2000, 10).group("General"));
    }

    @Override public void onDisable() {

        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null && prevSlot >= 0 && bool("switchBack")) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
        }
        seq = -1; prevSlot = -1; victim = null;
        HeldSlotManager.clear(this);
    }

    public boolean isActive() { return seq >= 0; }

    public LivingEntity victim() { return victim; }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null) return;

        if (!HeldSlotManager.holds(this) && prevSlot >= 0) {
            AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
            seq = -1; prevSlot = -1; victim = null;
            return;
        }

        if (seq < 0) {
            long now = System.currentTimeMillis();
            if (now - lastMs < PvpUtil.jitterMs(integer("delay"))) return;
            LivingEntity target = target(mc, p);
            if (target == null) return;

            if (target instanceof Player tp && (PvpUtil.isFriend(tp, BossPvpAddon.friends()) || (bool("teamCheck") && PvpUtil.isTeammate(p, tp)))) return;
            if (bool("onlyWhenBlocking") && !(target instanceof Player pl && pl.isBlocking())) return;
            int axe = findAxe(p);
            if (axe < 0) return;

            HeldSlotManager.request(this, HeldSlotManager.PRIORITY_SHIELDBREAKER);
            if (!HeldSlotManager.holds(this)) return;
            victim = target;
            prevSlot = p.getInventory().getSelectedSlot();
            seq = 0;
            lastMs = now;
        } else {
            HeldSlotManager.request(this, HeldSlotManager.PRIORITY_SHIELDBREAKER);
            if (!HeldSlotManager.holds(this)) return;
        }

        Step[] steps = Step.values();
        if (seq >= steps.length) { seq = -1; prevSlot = -1; victim = null; HeldSlotManager.release(this); return; }
        switch (steps[seq]) {
            case SWITCH_AXE -> {
                int axe = findAxe(p);
                if (axe >= 0) AutismInventoryHelper.selectHotbarSlot(mc, axe);
            }
            case HIT -> {

                if (victim != null && victim.isAlive() && p.distanceTo(victim) <= (float) decimal("range")) {
                    mc.gameMode.attack(p, victim);
                    p.swing(InteractionHand.MAIN_HAND);
                }
            }
            case SWITCH_BACK -> {
                if (bool("switchBack") && prevSlot >= 0) AutismInventoryHelper.selectHotbarSlot(mc, prevSlot);
                victim = null;
            }
        }
        seq++;
        if (seq >= steps.length) { seq = -1; prevSlot = -1; victim = null; HeldSlotManager.release(this); }
    }

    private LivingEntity target(Minecraft mc, LocalPlayer p) {
        if (BossPvpAddon.killAura != null && BossPvpAddon.killAura.isEnabled()
            && BossPvpAddon.killAura.currentTarget() != null) {
            return BossPvpAddon.killAura.currentTarget();
        }
        double rangeSq = decimal("range") * decimal("range");
        Player best = null;
        double bestD = rangeSq;
        for (Player pl : mc.level.players()) {
            if (pl == p || pl.isSpectator()) continue;
            if (PvpUtil.isFriend(pl, BossPvpAddon.friends()) || (bool("teamCheck") && PvpUtil.isTeammate(p, pl))) continue;
            double d = pl.distanceToSqr(p);
            if (d < bestD) { bestD = d; best = pl; }
        }
        return best;
    }

    private int findAxe(LocalPlayer p) {
        for (int i = 0; i <= 8; i++) {
            if (p.getInventory().getItem(i).is(ItemTags.AXES)) return i;
        }
        return -1;
    }
}
