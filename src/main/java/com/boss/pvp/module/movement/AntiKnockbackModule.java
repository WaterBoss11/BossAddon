package com.boss.pvp.module.movement;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;

import autismclient.modules.Module;
import autismclient.api.module.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

public final class AntiKnockbackModule extends Module {

    private static final int DAMP_WINDOW = 3;

    private int prevHurtTime = 0;
    private int dampTicks = 0;

    private boolean tapActive = false;
    private int tapTicksLeft = 0;
    private boolean restoreW = false;
    private boolean wtapPrimed = false;
    private boolean prevAttackDown = false;
    private long prevKaAttackMs = 0L;

    public AntiKnockbackModule() {
        super(BossPvpAddon.ID + ":antiknockback", "Anti-Knockback", "Reduces or cancels the knockback you take when hit. Defaults are safe for most servers.");

        add(new ChoiceSetting("mode", "Mode", "Reduce", "Reduce", "Cancel", "JumpReset").group("General"));
        add(new IntSetting("horizontal", "Horizontal reduction", 50, 0, 100, 1).formatter(v -> v + "%").group("General"));
        add(new IntSetting("vertical", "Vertical reduction", 30, 0, 100, 1).formatter(v -> v + "%").group("General"));
        add(new IntSetting("chance", "Chance to apply", 100, 0, 100, 1).formatter(v -> v + "%").group("General"));
        add(new BoolSetting("onlyWhileAttacking", "Only while attacking", false).group("General"));
        add(new BoolSetting("onlyPlayers", "Only when a player is near", false).group("General"));
        add(new BoolSetting("reduceWhileSprinting", "Reduce while sprinting", true).group("General"));

        add(new BoolSetting("wtap", "W-tap (sprint reset)", false).group("W-tap"));
        add(new ChoiceSetting("wtapTrigger", "Trigger", "On hit", "On hit", "On hurt", "Both")
            .description("On hit = right after you attack someone. On hurt = when you get knocked back. Both = either one.").group("W-tap"));
        add(new IntSetting("tapTicks", "Tap length", 1, 1, 3, 1)
            .description("How long to let go of W before pressing it again (in game ticks). 1 is usually best.").group("W-tap"));
        add(new BoolSetting("wtapOnlyForward", "Only while moving forward", true)
            .description("Skips the W-tap when you aren't holding W (there is nothing to reset).").group("W-tap"));
        add(new BoolSetting("superKb", "Super knockback (W-tap every hit)", false)
            .description("Briefly releases W on every attack so your hits knock enemies back further. Works even if W-tap is off.").group("W-tap"));
    }

    @Override
    public void onDisable() {
        prevHurtTime = 0;
        dampTicks = 0;
        endTap(Minecraft.getInstance());
        wtapPrimed = false;
        prevAttackDown = false;
        prevKaAttackMs = 0L;
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) { dampTicks = 0; prevHurtTime = 0; endTap(mc); wtapPrimed = false; return; }

        int hurt = p.hurtTime;
        boolean freshHit = hurt > prevHurtTime;

        if (bool("wtap") || bool("superKb")) handleWTap(mc, p, freshHit);
        else endTap(mc);

        if (freshHit) {
            boolean arm = PvpUtil.roll(integer("chance")) && (!bool("onlyPlayers") || playerNear(mc, p));
            dampTicks = arm ? DAMP_WINDOW : 0;
        }
        prevHurtTime = hurt;

        if (dampTicks <= 0) return;
        dampTicks--;

        if (bool("onlyWhileAttacking") && !attacking(mc)) return;
        if (!bool("reduceWhileSprinting") && p.isSprinting()) return;

        double keepH;
        double keepV;
        switch (choice("mode")) {
            case "Cancel" -> { keepH = 0.0; keepV = 0.0; }
            case "JumpReset" -> {
                if (!p.onGround()) return;
                keepH = 0.0; keepV = 0.0;
            }
            default -> {
                keepH = 1.0 - integer("horizontal") / 100.0;
                keepV = 1.0 - integer("vertical") / 100.0;
            }
        }

        Vec3 d = p.getDeltaMovement();

        double newY = d.y > 0 ? d.y * keepV : d.y;
        p.setDeltaMovement(d.x * keepH, newY, d.z * keepH);
    }

    private void handleWTap(Minecraft mc, LocalPlayer p, boolean freshHit) {
        if (mc.options == null || mc.gui.screen() != null) { endTap(mc); wtapPrimed = false; return; }

        boolean atkDown = mc.options.keyAttack.isDown();
        long kaMs = (BossPvpAddon.killAura != null) ? BossPvpAddon.killAura.lastAttackMs() : 0L;
        if (!wtapPrimed) {
            prevAttackDown = atkDown;
            prevKaAttackMs = kaMs;
            wtapPrimed = true;
            return;
        }

        if (tapActive) {
            if (--tapTicksLeft <= 0) endTap(mc);
            else mc.options.keyUp.setDown(false);
        }

        boolean attackEdge = atkDown && !prevAttackDown;
        prevAttackDown = atkDown;
        boolean kaHit = BossPvpAddon.killAura != null && BossPvpAddon.killAura.isEnabled() && kaMs != prevKaAttackMs;
        prevKaAttackMs = kaMs;
        boolean onHit = attackEdge || kaHit;

        boolean fire = bool("superKb") ? onHit : switch (choice("wtapTrigger")) {
            case "On hurt" -> freshHit;
            case "Both" -> onHit || freshHit;
            default -> onHit;
        };

        if (fire && !tapActive) {
            if (bool("wtapOnlyForward") && !mc.options.keyUp.isDown()) return;
            restoreW = mc.options.keyUp.isDown();
            tapActive = true;
            tapTicksLeft = Math.max(1, integer("tapTicks"));
            mc.options.keyUp.setDown(false);
        }
    }

    private void endTap(Minecraft mc) {
        if (!tapActive) return;
        if (mc != null && mc.options != null) mc.options.keyUp.setDown(restoreW);
        tapActive = false;
        tapTicksLeft = 0;
    }

    private boolean playerNear(Minecraft mc, LocalPlayer p) {
        if (mc.level == null) return false;
        for (Player pl : mc.level.players()) {
            if (pl == p || pl.isSpectator()) continue;
            if (pl.distanceToSqr(p) <= 64.0) return true;
        }
        return false;
    }

    private boolean attacking(Minecraft mc) {
        if (mc.options != null && mc.options.keyAttack.isDown()) return true;
        return BossPvpAddon.killAura != null && BossPvpAddon.killAura.isEnabled()
            && BossPvpAddon.killAura.currentTarget() != null;
    }
}
