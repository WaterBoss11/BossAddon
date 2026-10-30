package com.boss.pvp.module.combat;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.KeybindSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.InputUtil;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.PvpUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.HitResult;

/** Pearl-only adaptation of Nami ClickPearl; the MIT notice is packaged with this addon. */
public final class ClickPearlModule extends Module {
    private boolean wasPressed;

    public ClickPearlModule() {
        super(BossPvpAddon.ID + ":clickpearl", "ClickPearl", "Throws one pearl per bound-key press, then restores your slot.");
        add(new KeybindSetting("throwKey", "Throw pearl", -1));
        add(new BoolSetting("entityCheck", "Avoid entity interactions", true));
    }

    @Override
    public void onEnable() {
        wasPressed = InputUtil.isBindPressed(Minecraft.getInstance(), integer("throwKey"));
    }

    public void tick(Minecraft mc) {
        boolean pressed = InputUtil.isBindPressed(mc, integer("throwKey"));
        boolean trigger = RangedCombatDecisions.risingEdge(wasPressed, pressed);
        wasPressed = pressed;
        if (!trigger || !InputUtil.canRunKeybinds(mc)) return;
        if (bool("entityCheck") && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY) return;
        PvpUtil.useHotbarItem(mc, this, 75, Items.ENDER_PEARL, mc.player.getYRot(), mc.player.getXRot());
    }

    @Override
    public void onDisable() {
        wasPressed = false;
        HeldSlotManager.clear(this);
    }

    @Override public void onGameLeft() { onDisable(); }
    @Override public void onGameJoin() { onEnable(); }
}
