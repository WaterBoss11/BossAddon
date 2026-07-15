package com.boss.pvp.module.automation;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;
import com.boss.pvp.util.pvp.ArmorScorer;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismBindUtil;
import autismclient.util.AutismInputGate;
import autismclient.util.AutismInventoryHelper;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class AutoArmorModule extends Module {

    private static final int ARMOR_HEAD = 5, ARMOR_CHEST = 6, ARMOR_LEGS = 7, ARMOR_FEET = 8;
    private static final int FIRST_STORAGE_SLOT = 9, LAST_HOTBAR_SLOT = 44;

    private static final Item[] NETHERITE = {Items.NETHERITE_HELMET, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_LEGGINGS, Items.NETHERITE_BOOTS};
    private static final Item[] DIAMOND   = {Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS};
    private static final Item[] IRON      = {Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS};
    private static final Item[] CHAINMAIL = {Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS};
    private static final Item[] GOLD      = {Items.GOLDEN_HELMET, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_LEGGINGS, Items.GOLDEN_BOOTS};
    private static final Item[] LEATHER   = {Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS};

    private static final int[] AP_LEATHER   = {1, 3, 2, 1};
    private static final int[] AP_GOLD      = {2, 5, 3, 1};
    private static final int[] AP_CHAIN     = {2, 5, 4, 1};
    private static final int[] AP_IRON      = {2, 6, 5, 2};
    private static final int[] AP_DIAMOND   = {3, 8, 6, 3};
    private static final int[] AP_NETHERITE = {3, 8, 6, 3};

    private static final int DURABILITY_FLOOR_PCT = 5;

    private static final double UPGRADE_MARGIN = 6.0;

    private long lastCheckMs = 0L;
    private boolean prevAddHeldDown = false;
    private boolean prevAddPotDown = false;

    private static final String POTION_KEEP_CONFIG_KEY = "autoArmorPotionKeep";
    private static final String DEFAULT_POTION_KEEP = String.join(",",
        "instant_health", "regeneration", "strong_healing", "swiftness", "strength", "fire_resistance", "water_breathing");

    public AutoArmorModule() {
        super(BossPvpAddon.ID + ":autoarmor", "AutoArmor", "Auto-equip the best armor from your inventory, one piece per tick.");

        add(new BoolSetting("preferEnchanted", "Prefer enchanted", true)
            .description("ON = enchants dominate the ranking (recommended). OFF = material tier dominates instead.").group("General"));
        add(new BoolSetting("preferBlast", "Prefer Blast Protection", true)
            .description("Extra weight on Blast Protection levels (crystal/anchor PvP).").group("General"));
        add(new IntSetting("durabilitySwap", "Durability swap below %", 10, 0, 100, 1).formatter(v -> v + "%").group("General"));
        add(new BoolSetting("pauseInCombat", "Pause when enemy near", false).group("General"));
        add(new BoolSetting("onlyNoContainer", "Only when no container open", true).group("General"));
        add(new IntSetting("delay", "Delay (ms)", 100, 0, 1000, 10).group("General"));

        add(new BoolSetting("autoDrop", "Auto-drop worse armor", false)
            .description("Drop inventory armor that isn't a strict upgrade over what you're wearing. Destructive.").group("Auto-drop"));
        add(new BoolSetting("keepSpare", "Keep one spare per slot", true)
            .description("Keep a single equal-quality backup per slot instead of dropping every spare.").group("Auto-drop"));

        add(new BoolSetting("dropJunk", "Drop junk (keep-list)", false)
            .description("Drop anything NOT on the keep-list. Destructive — edit the keep-list first.").group("Drop junk"));
        add(new BoolSetting("keepBestTool", "Keep best tool only", true)
            .description("For kept weapons/tools, keep the single best per category and drop duplicates.").group("Drop junk"));
        add(RegistryListSetting.items("keepList", "Keep list", DEFAULT_KEEP_LIST)
            .description("Item types to KEEP. Everything else is junk. Defaults to the standard PvP kit.").group("Drop junk"));

        add(new KeybindSetting("addHeldKey", "Add/remove held item to Keep-list", -1)
            .description("Default unbound — bind it, then hold an item and tap to toggle it on the keep-list.").group("Drop junk"));
        add(new ActionSetting("snapshot", "Snapshot inventory -> Keep-list", this::snapshotKeepList)
            .description("Replace the keep-list with every item type currently in your inventory. Use on a clean kit.").group("Drop junk"));

        add(new KeybindSetting("addPotKey", "Add/remove held POTION to Pot-keep-list", -1)
            .description("Default unbound — bind it, then hold a potion and tap to toggle its effect on the pot keep-list.").group("Drop junk"));
        add(new ActionSetting("snapshotPots", "Snapshot inventory potions -> Pot-keep-list", this::snapshotPotionKeep)
            .description("Replace the pot keep-list with every potion effect currently in your inventory. Use on a clean kit.").group("Drop junk"));
    }

    private static final String DEFAULT_KEEP_LIST = String.join(",",

        "minecraft:netherite_sword", "minecraft:diamond_sword", "minecraft:iron_sword", "minecraft:golden_sword",
        "minecraft:stone_sword", "minecraft:wooden_sword",
        "minecraft:netherite_axe", "minecraft:diamond_axe", "minecraft:iron_axe", "minecraft:golden_axe",
        "minecraft:stone_axe", "minecraft:wooden_axe", "minecraft:mace",

        "minecraft:netherite_helmet", "minecraft:netherite_chestplate", "minecraft:netherite_leggings", "minecraft:netherite_boots",
        "minecraft:diamond_helmet", "minecraft:diamond_chestplate", "minecraft:diamond_leggings", "minecraft:diamond_boots",
        "minecraft:iron_helmet", "minecraft:iron_chestplate", "minecraft:iron_leggings", "minecraft:iron_boots",
        "minecraft:chainmail_helmet", "minecraft:chainmail_chestplate", "minecraft:chainmail_leggings", "minecraft:chainmail_boots",
        "minecraft:golden_helmet", "minecraft:golden_chestplate", "minecraft:golden_leggings", "minecraft:golden_boots",
        "minecraft:leather_helmet", "minecraft:leather_chestplate", "minecraft:leather_leggings", "minecraft:leather_boots",
        "minecraft:turtle_helmet",

        "minecraft:totem_of_undying", "minecraft:end_crystal", "minecraft:enchanted_golden_apple", "minecraft:golden_apple",
        "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:ender_pearl", "minecraft:glowstone",
        "minecraft:respawn_anchor", "minecraft:splash_potion", "minecraft:lingering_potion");

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) return;

        handleKeepListKeybind(mc, p);
        handlePotionKeybind(mc, p);

        boolean containerOpen = p.containerMenu != p.inventoryMenu;
        if (containerOpen) {
            if (bool("onlyNoContainer")) return;
        }
        if (mc.gui.screen() != null && bool("onlyNoContainer")) return;
        if (bool("pauseInCombat") && enemyNear(mc, p)) return;

        long now = System.currentTimeMillis();
        if (now - lastCheckMs < PvpUtil.jitterMs(integer("delay"))) return;
        lastCheckMs = now;

        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        AbstractContainerMenu menu = p.inventoryMenu;
        int threshold = integer("durabilitySwap");
        for (EquipmentSlot slot : slots) {
            ItemStack equipped = p.getItemBySlot(slot);
            int candHandler = bestCandidateHandlerSlot(p, menu, slot);
            if (candHandler < 0) continue;
            ItemStack candidate = menu.slots.get(candHandler).getItem();
            if (!shouldReplace(p, equipped, candidate, slot, threshold)) continue;

            AutismInventoryHelper.swapHandlerSlots(mc, candHandler, armorHandlerSlot(slot));
            return;
        }

        if (!containerOpen && mc.gui.screen() == null) {
            if (bool("autoDrop") && tryAutoDrop(mc, p, menu)) return;
            if (bool("dropJunk")) tryDropJunk(mc, p, menu);
        }
    }

    private int bestCandidateHandlerSlot(LocalPlayer p, AbstractContainerMenu menu, EquipmentSlot slot) {

        int bestAbove = -1; double bestAboveScore = -1.0;
        int bestAny = -1;   double bestAnyScore = -1.0;
        for (int i = FIRST_STORAGE_SLOT; i <= LAST_HOTBAR_SLOT && i < menu.slots.size(); i++) {
            Slot s = menu.slots.get(i);
            if (s == null) continue;
            ItemStack stack = s.getItem();
            if (stack == null || stack.isEmpty()) continue;
            if (materialRank(stack) <= 0) continue;
            if (p.getEquipmentSlotForItem(stack) != slot) continue;
            double sc = score(stack, slot);
            if (sc > bestAnyScore) { bestAnyScore = sc; bestAny = i; }
            if ((!stack.isDamageableItem() || remainingPct(stack) >= DURABILITY_FLOOR_PCT) && sc > bestAboveScore) {
                bestAboveScore = sc; bestAbove = i;
            }
        }
        return bestAbove >= 0 ? bestAbove : bestAny;
    }

    private boolean shouldReplace(LocalPlayer p, ItemStack equipped, ItemStack candidate, EquipmentSlot slot, int thresholdPct) {
        if (candidate == null || candidate.isEmpty()) return false;
        if (equipped == null || equipped.isEmpty()) return true;
        if (score(candidate, slot) > score(equipped, slot) + UPGRADE_MARGIN) return true;

        if (equipped.isDamageableItem() && remainingPct(equipped) < thresholdPct
                && materialRank(candidate) >= materialRank(equipped)
                && remainingPct(candidate) > remainingPct(equipped)) {
            return true;
        }
        return false;
    }

    private double score(ItemStack stack, EquipmentSlot slot) {
        return ArmorScorer.score(stack, slot, bool("preferEnchanted"), bool("preferBlast"));
    }

    private int remainingPct(ItemStack stack) {
        return ArmorScorer.remainingPct(stack);
    }

    private int materialRank(ItemStack s) {
        return ArmorScorer.materialRank(s);
    }

    private boolean in(ItemStack s, Item[] arr) {
        for (Item i : arr) if (s.is(i)) return true;
        return false;
    }

    private boolean tryAutoDrop(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu) {
        boolean keepSpare = bool("keepSpare");
        EquipmentSlot[] slots = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot slot : slots) {
            ItemStack equipped = p.getItemBySlot(slot);

            List<Integer> droppable = new ArrayList<>();
            for (int i = FIRST_STORAGE_SLOT; i <= LAST_HOTBAR_SLOT && i < menu.slots.size(); i++) {
                Slot s = menu.slots.get(i);
                if (s == null) continue;
                ItemStack st = s.getItem();
                if (st == null || st.isEmpty()) continue;
                if (materialRank(st) <= 0) continue;
                if (p.getEquipmentSlotForItem(st) != slot) continue;
                if (isStrictUpgrade(st, equipped, slot)) continue;
                droppable.add(i);
            }
            if (droppable.isEmpty()) continue;

            int spareId = -1;
            if (keepSpare) {
                if (droppable.size() <= 1) continue;
                spareId = bestScoringSlot(menu, droppable, slot);
            }

            int worst = -1; double worstScore = Double.MAX_VALUE;
            for (int id : droppable) {
                if (id == spareId) continue;
                double sc = score(menu.slots.get(id).getItem(), slot);
                if (sc < worstScore) { worstScore = sc; worst = id; }
            }
            if (worst >= 0) { dropStack(mc, p, worst); return true; }
        }
        return false;
    }

    private boolean isStrictUpgrade(ItemStack candidate, ItemStack equipped, EquipmentSlot slot) {
        if (equipped == null || equipped.isEmpty()) return true;
        return score(candidate, slot) > score(equipped, slot) + UPGRADE_MARGIN;
    }

    private int bestScoringSlot(AbstractContainerMenu menu, List<Integer> ids, EquipmentSlot slot) {
        int best = -1; double bestScore = -1.0;
        for (int id : ids) {
            double sc = score(menu.slots.get(id).getItem(), slot);
            if (sc > bestScore) { bestScore = sc; best = id; }
        }
        return best;
    }

    private void dropStack(Minecraft mc, LocalPlayer p, int menuSlotId) {

        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, menuSlotId, 1, ContainerInput.THROW, p);
    }

    private boolean tryDropJunk(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu) {
        Set<String> keep = keepSet();
        Set<String> potKeep = potionKeepSet();
        int heldMenuSlot = 36 + p.getInventory().getSelectedSlot();

        for (int i = FIRST_STORAGE_SLOT; i <= LAST_HOTBAR_SLOT && i < menu.slots.size(); i++) {
            if (i == heldMenuSlot) continue;
            ItemStack st = itemAt(menu, i);
            if (st == null) continue;

            if (isPotionItem(st)) {
                if (!isKeepPotion(st, potKeep)) { dropStack(mc, p, i); return true; }
                continue;
            }
            if (!onKeepList(st, keep)) { dropStack(mc, p, i); return true; }
        }

        if (bool("keepBestTool")) {

            Map<String, Double> bestScore = new HashMap<>();
            for (int i = FIRST_STORAGE_SLOT; i <= LAST_HOTBAR_SLOT && i < menu.slots.size(); i++) {
                ItemStack st = itemAt(menu, i);
                if (st == null) continue;
                String cat = toolCategory(st);
                if (cat == null || !onKeepList(st, keep)) continue;
                double sc = toolScore(st);
                bestScore.merge(cat, sc, Math::max);
            }

            for (int i = FIRST_STORAGE_SLOT; i <= LAST_HOTBAR_SLOT && i < menu.slots.size(); i++) {
                if (i == heldMenuSlot) continue;
                ItemStack st = itemAt(menu, i);
                if (st == null) continue;
                String cat = toolCategory(st);
                if (cat == null || !onKeepList(st, keep)) continue;
                if (toolScore(st) < bestScore.getOrDefault(cat, 0.0)) { dropStack(mc, p, i); return true; }
            }
        }
        return false;
    }

    private ItemStack itemAt(AbstractContainerMenu menu, int i) {
        Slot s = menu.slots.get(i);
        if (s == null) return null;
        ItemStack st = s.getItem();
        return (st == null || st.isEmpty()) ? null : st;
    }

    private Set<String> keepSet() {
        return orderedKeepTokens();
    }

    private void handleKeepListKeybind(Minecraft mc, LocalPlayer p) {
        int key = parseKey(value("addHeldKey"));
        if (key == -1 || !AutismInputGate.canRunAutismKeybinds()) { prevAddHeldDown = false; return; }
        boolean down = AutismBindUtil.isBindPressed(mc, key);
        if (down && !prevAddHeldDown) toggleHeldInKeepList(p);
        prevAddHeldDown = down;
    }

    private int parseKey(String s) {
        if (s == null) return -1;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
    }

    private void toggleHeldInKeepList(LocalPlayer p) {
        ItemStack held = p.getMainHandItem();
        if (held == null || held.isEmpty()) { chat(p, "Keep-list: hold an item to add/remove"); return; }
        String id = BuiltInRegistries.ITEM.getKey(held.getItem()).toString().toLowerCase(Locale.ROOT);
        String shortId = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        Set<String> set = orderedKeepTokens();
        String name = held.getHoverName().getString();
        if (set.remove(id) | set.remove(shortId)) {
            setValue("keepList", String.join(",", set));
            chat(p, "Keep-list - " + name);
        } else {
            set.add(id);
            setValue("keepList", String.join(",", set));
            chat(p, "Keep-list + " + name);
        }
    }

    private void snapshotKeepList() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        LocalPlayer p = mc.player;
        Set<String> set = new LinkedHashSet<>();
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.isEmpty()) continue;
            set.add(BuiltInRegistries.ITEM.getKey(st.getItem()).toString().toLowerCase(Locale.ROOT));
        }
        if (set.isEmpty()) { chat(p, "Keep-list: inventory empty, unchanged"); return; }
        setValue("keepList", String.join(",", set));
        chat(p, "Keep-list: snapshot " + set.size() + " item types");
    }

    private Set<String> orderedKeepTokens() {
        Set<String> out = new LinkedHashSet<>();
        for (String entry : list("keepList")) {
            if (entry == null) continue;
            for (String part : entry.split(",")) {
                String v = part.trim().toLowerCase(Locale.ROOT);
                if (!v.isEmpty()) out.add(v);
            }
        }
        return out;
    }

    private boolean isPotionItem(ItemStack st) {
        return st.is(Items.POTION) || st.is(Items.SPLASH_POTION) || st.is(Items.LINGERING_POTION);
    }

    private Set<String> potionKeepSet() {
        Set<String> out = new LinkedHashSet<>();
        String csv = BossPvpAddon.getConfigString(POTION_KEEP_CONFIG_KEY, DEFAULT_POTION_KEEP);
        for (String part : csv.split(",")) {
            String v = part.trim().toLowerCase(Locale.ROOT);
            if (!v.isEmpty()) out.add(stripNamespace(v));
        }
        return out;
    }

    private String stripNamespace(String id) {
        int c = id.indexOf(':');
        return c >= 0 ? id.substring(c + 1) : id;
    }

    private boolean isKeepPotion(ItemStack st, Set<String> potKeep) {
        PotionContents pc = st.get(DataComponents.POTION_CONTENTS);
        if (pc == null) return true;
        boolean sawAny = false;
        if (pc.potion().isPresent()) {
            sawAny = true;
            String pid = stripNamespace(pc.potion().get().getRegisteredName().toLowerCase(Locale.ROOT));
            if (potKeep.contains(pid)) return true;
        }
        for (MobEffectInstance eff : pc.getAllEffects()) {
            sawAny = true;
            String eid = stripNamespace(eff.getEffect().getRegisteredName().toLowerCase(Locale.ROOT));
            if (potKeep.contains(eid)) return true;
        }
        return !sawAny;
    }

    private String potionToken(ItemStack st) {
        PotionContents pc = st.get(DataComponents.POTION_CONTENTS);
        if (pc == null) return null;
        if (pc.potion().isPresent()) {
            return stripNamespace(pc.potion().get().getRegisteredName().toLowerCase(Locale.ROOT));
        }
        for (MobEffectInstance eff : pc.getAllEffects()) {
            return stripNamespace(eff.getEffect().getRegisteredName().toLowerCase(Locale.ROOT));
        }
        return null;
    }

    private void handlePotionKeybind(Minecraft mc, LocalPlayer p) {
        int key = parseKey(value("addPotKey"));
        if (key == -1 || !AutismInputGate.canRunAutismKeybinds()) { prevAddPotDown = false; return; }
        boolean down = AutismBindUtil.isBindPressed(mc, key);
        if (down && !prevAddPotDown) toggleHeldPotionInKeepList(p);
        prevAddPotDown = down;
    }

    private void toggleHeldPotionInKeepList(LocalPlayer p) {
        ItemStack held = p.getMainHandItem();
        if (held == null || held.isEmpty() || !isPotionItem(held)) { chat(p, "Pot keep-list: hold a potion to add/remove"); return; }
        String token = potionToken(held);
        if (token == null) { chat(p, "Pot keep-list: unreadable potion, unchanged"); return; }
        Set<String> set = potionKeepSet();
        String name = held.getHoverName().getString();
        if (set.remove(token)) {
            BossPvpAddon.setConfigString(POTION_KEEP_CONFIG_KEY, String.join(",", set));
            chat(p, "Pot keep-list - " + name + " (" + token + ")");
        } else {
            set.add(token);
            BossPvpAddon.setConfigString(POTION_KEEP_CONFIG_KEY, String.join(",", set));
            chat(p, "Pot keep-list + " + name + " (" + token + ")");
        }
    }

    private void snapshotPotionKeep() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return;
        LocalPlayer p = mc.player;
        Set<String> set = new LinkedHashSet<>();
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st == null || st.isEmpty() || !isPotionItem(st)) continue;
            String token = potionToken(st);
            if (token != null) set.add(token);
        }
        if (set.isEmpty()) { chat(p, "Pot keep-list: no potions in inventory, unchanged"); return; }
        BossPvpAddon.setConfigString(POTION_KEEP_CONFIG_KEY, String.join(",", set));
        chat(p, "Pot keep-list: snapshot " + set.size() + " potion types");
    }

    private void chat(LocalPlayer p, String msg) {
        p.sendSystemMessage(Component.literal("[AutoArmor] " + msg));
    }

    private boolean onKeepList(ItemStack st, Set<String> keep) {
        if (keep.isEmpty()) return true;
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString().toLowerCase(Locale.ROOT);
        if (keep.contains(id)) return true;
        int c = id.indexOf(':');
        return c >= 0 && keep.contains(id.substring(c + 1));
    }

    private String toolCategory(ItemStack st) {
        if (st.is(ItemTags.SWORDS)) return "sword";
        if (st.is(Items.MACE)) return "mace";
        if (st.is(ItemTags.AXES)) return "axe";
        if (st.is(ItemTags.PICKAXES)) return "pickaxe";
        if (st.is(ItemTags.SHOVELS)) return "shovel";
        if (st.is(ItemTags.HOES)) return "hoe";
        return null;
    }

    private double toolScore(ItemStack st) {
        double ench = 0.0;
        if (st.isEnchanted()) for (var e : st.getEnchantments().entrySet()) ench += Math.max(0, e.getIntValue());
        return toolMaterialRank(st) * 1000.0 + ench * 8.0 + remainingPct(st) * 0.05;
    }

    private int toolMaterialRank(ItemStack st) {
        if (st.is(Items.MACE)) return 6;
        String id = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
        String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
        if (path.startsWith("netherite_")) return 6;
        if (path.startsWith("diamond_"))   return 5;
        if (path.startsWith("iron_"))      return 4;
        if (path.startsWith("stone_"))     return 3;
        if (path.startsWith("golden_"))    return 2;
        if (path.startsWith("wooden_"))    return 1;
        return 0;
    }

    private boolean enemyNear(Minecraft mc, LocalPlayer p) {
        double r = 8.0;
        for (Player pl : mc.level.players()) {
            if (pl == p || pl.isSpectator()) continue;
            if (pl.distanceToSqr(p) <= r * r) return true;
        }
        return false;
    }

    private int armorHandlerSlot(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> ARMOR_HEAD;
            case CHEST -> ARMOR_CHEST;
            case LEGS -> ARMOR_LEGS;
            default -> ARMOR_FEET;
        };
    }
}
