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
            "Attacks whatever your crosshair is pointing at — you aim, it clicks.");

        add(RegistryListSetting.entityTypes("entities", "Targets", "minecraft:player"));
        add(new DoubleSetting("range", "Range", 3.0, 1.0, 3.5, 0.1)
            .description("Attack reach. 3.0 is the normal game value; higher may be flagged on strict anticheats."));
        add(new IntSetting("delay", "Delay (ms)", 100, 0, 1000, 10)
            .description("Time between hits. Varies slightly on its own (+-25%) so the click rate doesn't look robotic."));
        add(new IntSetting("hurtTime", "Hurt time", 10, 0, 10, 1)
            .description("How faded the target's red damage flash must be before hitting again. 10 = hit as soon as possible; lower = wait longer."));
        add(new BoolSetting("fullCharge", "Require full charge", true)
            .description("Waits for your attack bar to fully charge so every hit does full damage."));
        add(new BoolSetting("onlyWhileWeapon", "Only while holding a weapon", false));
        add(new BoolSetting("swing", "Swing arm", true));
        add(new BoolSetting("teamCheck", "Team check", false)
            .description("Skips players wearing leather armor dyed the same color as yours (likely teammates).").group("Team"));
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

        if (target instanceof Player pl && (PvpUtil.isFriend(pl, BossPvpAddon.friends()) || (bool("teamCheck") && PvpUtil.isTeammate(p, pl)))) return;

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
