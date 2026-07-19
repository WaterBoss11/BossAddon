package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.input.OffhandManager;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismClientMessaging;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

public final class AutoTotemModule extends Module {

    private static final int OFFHAND_SLOT_ID = 45;
    private static final int OFFHAND_SWAP_BUTTON = 40;

    private long lastMoveMs = 0L;
    private boolean wasTotemOffhand = false;
    private int popCount = 0;

    public AutoTotemModule() {
        super(BossPvpAddon.ID + ":autototem", "AutoTotem", "Keeps a totem in your offhand and replaces it right after it pops.");

        add(new IntSetting("delay", "Delay (ms)", 100, 0, 1000, 10).group("General"));
        add(new BoolSetting("pauseInGuis", "Pause in containers", true).group("General"));

        add(new ChoiceSetting("mode", "Mode", "Always", "Always", "Smart").group("General"));
        add(new DoubleSetting("health", "Smart: totem below HP", 12.0, 1.0, 19.0, 0.5).group("General"));
        add(new BoolSetting("instantRetotem", "Instant re-totem", true).group("General"));
        add(new BoolSetting("mainhandFallback", "Use main hand if offhand has a shield", true).group("General"));
        add(new BoolSetting("popAlert", "Chat alert on pop", true).group("General"));
        add(new BoolSetting("predictCrystal", "Smart: re-totem if crystal nearby", false).group("General"));
    }

    @Override
    public void onDisable() {
        wasTotemOffhand = false;
        OffhandManager.clear(this);
    }

    public int popCount() { return popCount; }

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) return;

        OffhandManager.request(this, OffhandManager.PRIORITY_AUTOTOTEM);

        boolean hasTotem = p.getOffhandItem().is(Items.TOTEM_OF_UNDYING);

        if (wasTotemOffhand && !hasTotem) {
            popCount++;
            if (bool("popAlert")) AutismClientMessaging.sendPrefixed("§c[AutoTotem] popped! (" + popCount + ")");
        }
        wasTotemOffhand = hasTotem;

        if (hasTotem) return;

        if ("Smart".equals(choice("mode")) && p.getHealth() > (float) decimal("health")
                && !(bool("predictCrystal") && crystalNear(mc, p))) {
            return;
        }

        boolean containerOpen = p.containerMenu != p.inventoryMenu;
        if (containerOpen && bool("pauseInGuis")) return;

        long now = System.currentTimeMillis();
        long delay = bool("instantRetotem") ? 0L : integer("delay");
        if (now - lastMoveMs < delay) return;

        AbstractContainerMenu menu = p.inventoryMenu;
        int totemSlotId = findTotemSlot(menu);
        if (totemSlotId < 0) return;

        if (!OffhandManager.mayWrite(this)) return;

        if (bool("mainhandFallback") && p.getOffhandItem().is(Items.SHIELD)) {

            mc.gameMode.handleContainerInput(menu.containerId, totemSlotId, p.getInventory().getSelectedSlot(),
                ContainerInput.SWAP, p);
            lastMoveMs = now;
            com.boss.pvp.util.CombatManager.pauseCombat(2);
            return;
        }

        mc.gameMode.handleContainerInput(menu.containerId, totemSlotId, OFFHAND_SWAP_BUTTON, ContainerInput.SWAP, p);
        lastMoveMs = now;
        com.boss.pvp.util.CombatManager.pauseCombat(2);
    }

    public int totemCount() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return 0;
        var inv = mc.player.getInventory();
        int c = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(Items.TOTEM_OF_UNDYING)) c += inv.getItem(i).getCount();
        }
        return c;
    }

    private boolean crystalNear(Minecraft mc, LocalPlayer p) {
        double r = 6.0;
        AABB box = p.getBoundingBox().inflate(r);
        return !mc.level.getEntitiesOfClass(EndCrystal.class, box, e -> e.isAlive()).isEmpty();
    }

    private int findTotemSlot(AbstractContainerMenu menu) {
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i == OFFHAND_SLOT_ID) continue;
            Slot slot = menu.slots.get(i);
            if (slot == null) continue;
            ItemStack stack = slot.getItem();
            if (stack != null && stack.is(Items.TOTEM_OF_UNDYING)) return i;
        }
        return -1;
    }
}
