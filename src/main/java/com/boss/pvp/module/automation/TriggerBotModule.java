package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;

import autismclient.modules.Module;
import autismclient.api.module.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.EntityHitResult;

import java.util.Set;

public final class TriggerBotModule extends Module {

    private long lastAttackMs = 0L;
    private long nextDelayMs = 0L;

    public TriggerBotModule() {
        super(BossPvpAddon.ID + ":triggerbot", "TriggerBot",
            "Auto-attack the entity under your crosshair. No rotation — you aim, it clicks.");

        add(RegistryListSetting.entityTypes("entities", "Entities", "minecraft:player"));
        add(new DoubleSetting("range", "Range", 3.0, 1.0, 3.5, 0.1)
            .description("Attack reach. Vanilla is 3.0; above is opt-in and may flag on strict anticheats."));
        add(new IntSetting("delay", "Delay (ms)", 100, 0, 1000, 10)
            .description("Base delay between hits; jittered +-25% so the click rate isn't robotic."));
        add(new IntSetting("hurtTime", "Hurt Time", 10, 0, 10, 1)
            .description("Only hit once the target's hurt-flash has dropped to/below this (allows a fresh hit)."));
        add(new BoolSetting("fullCharge", "Require full charge", true)
            .description("Only attack when the attack-cooldown is fully charged (max-damage hits)."));
        add(new BoolSetting("onlyWhileWeapon", "Only while holding weapon", false));
        add(new BoolSetting("swing", "Swing", true));
        add(new BoolSetting("teamCheck", "Team check", false)
            .description("Skip players wearing leather armour dyed the same colour as yours (teammates).").group("Team"));
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null) return;

        if (bool("onlyWhileWeapon") && !isWeapon(p.getMainHandItem())) return;

        if (!(mc.hitResult instanceof EntityHitResult ehr)) return;
        Entity hit = ehr.getEntity();
        if (!(hit instanceof LivingEntity target)) return;
        if (target == p || target.isRemoved() || !target.isAlive()) return;

        Set<String> ids = PvpUtil.entityIds(list("entities"));
        if (!PvpUtil.matchesEntity(target, ids)) return;

        double range = decimal("range");
        if (target.distanceToSqr(p) > range * range) return;

        if (bool("teamCheck") && target instanceof Player pl && PvpUtil.isTeammate(p, pl)) return;

        if (target.hurtTime > integer("hurtTime")) return;

        long now = System.currentTimeMillis();
        if (now - lastAttackMs < nextDelayMs) return;
        if (bool("fullCharge") && !PvpUtil.fullCharge(p)) return;

        mc.gameMode.attack(p, target);
        if (bool("swing")) p.swing(InteractionHand.MAIN_HAND);

        lastAttackMs = now;
        nextDelayMs = PvpUtil.jitterMs(integer("delay"));
    }

    private boolean isWeapon(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES);
    }
}
