package com.boss.pvp.module.combat;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.MenuMode;

import autismclient.modules.Module;
import autismclient.api.module.*;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;

public final class ReachModule extends Module {

    private static final double VANILLA_ATTACK = 3.0;
    private static final double VANILLA_INTERACT = 4.5;
    private static final long COMBAT_WINDOW_MS = 1000L;

    public ReachModule() {
        super(BossPvpAddon.ID + ":reach", "Reach",
            "Attack and reach blocks from farther away.");

        add(new DoubleSetting("attackRange", "Attack range", 3.0, 3.0, 6.0, 0.1)
            .description("How far you can hit players and mobs. Vanilla is 3.0.").group("General"));
        add(new DoubleSetting("interactRange", "Interact range", 4.5, 4.5, 6.0, 0.1)
            .description("How far you can place, break, and use blocks. Vanilla is 4.5.").group("General"));
        add(new BoolSetting("onlyInCombat", "Only in combat", false)
            .description("Only extend reach while KillAura has a target or you attacked in the last second.")
            .visibleWhen(MenuMode::advanced)
            .group("General"));
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            setBase(mc.player, Attributes.ENTITY_INTERACTION_RANGE, VANILLA_ATTACK);
            setBase(mc.player, Attributes.BLOCK_INTERACTION_RANGE, VANILLA_INTERACT);
        }
    }

    /** The configured attack range. KillAura reads this while Reach is enabled so its attack gate
     *  matches the extended reach (they were previously out of sync — KillAura capped itself at 3.5). */
    public double getAttackRange() {
        return decimal("attackRange");
    }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        boolean extend = !bool("onlyInCombat") || inCombat();
        double atk = extend ? decimal("attackRange") : VANILLA_ATTACK;
        double itr = extend ? decimal("interactRange") : VANILLA_INTERACT;

        setBase(p, Attributes.ENTITY_INTERACTION_RANGE, atk);
        setBase(p, Attributes.BLOCK_INTERACTION_RANGE, itr);
    }

    private boolean inCombat() {
        KillAuraModule ka = BossPvpAddon.killAura;
        if (ka == null) return false;
        if (ka.currentTarget() != null) return true;
        return System.currentTimeMillis() - ka.lastAttackMs() < COMBAT_WINDOW_MS;
    }

    private static void setBase(LocalPlayer p, Holder<Attribute> attr, double value) {
        AttributeInstance inst = p.getAttribute(attr);
        if (inst != null && inst.getBaseValue() != value) {
            inst.setBaseValue(value);
        }
    }
}
