package com.boss.pvp.module.combat;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.InputUtil;
import com.boss.pvp.util.pvp.PvpUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;

/** Native charge-state implementation of Nami's MIT-licensed bow-release behavior. */
public final class BowReleaseModule extends Module {
    public BowReleaseModule() {
        super(BossPvpAddon.ID + ":bowrelease", "BowRelease", "Releases manually drawn bows or tridents at the configured charge.");
        add(new IntSetting("bowTicks", "Bow charge ticks", 20, 1, 40, 1));
        add(new BoolSetting("tridents", "Release tridents", true));
        add(new IntSetting("tridentTicks", "Trident charge ticks", TridentItem.THROW_THRESHOLD_TIME, TridentItem.THROW_THRESHOLD_TIME, 40, 1));
        add(new BoolSetting("manualOnly", "Require physical use input", true));
    }

    public void tick(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.gameMode == null || mc.gui.screen() != null
                || !mc.player.isAlive() || !mc.player.isUsingItem() || PvpUtil.hostEnabled("hide")) return;
        if (bool("manualOnly") && !InputUtil.physicallyDown(mc, mc.options.keyUse)) return;
        var stack = mc.player.getUseItem();
        int required;
        if (stack.is(Items.BOW)) required = integer("bowTicks");
        else if (bool("tridents") && stack.is(Items.TRIDENT)) required = integer("tridentTicks");
        else return;
        if (RangedCombatDecisions.chargeReady(mc.player.getTicksUsingItem(), required)) {
            mc.gameMode.releaseUsingItem(mc.player);
        }
    }
}
