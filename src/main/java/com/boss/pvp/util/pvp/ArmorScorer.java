package com.boss.pvp.util.pvp;

import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;

public final class ArmorScorer {

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

    private ArmorScorer() {}

    public static double score(ItemStack stack, EquipmentSlot slot, boolean preferEnchanted, boolean preferBlast) {
        double base  = baseArmorPoints(stack, slot) * 10.0;
        double tough = toughnessPoints(stack) * 10.0;
        double ench  = preferEnchanted ? enchantScore(stack, slot, preferBlast) : 0.0;
        double dura  = remainingPct(stack) * 0.05;
        return base + tough + ench + dura;
    }

    public static double enchantScore(ItemStack stack, EquipmentSlot slot, boolean preferBlast) {
        if (stack == null || stack.isEmpty() || !stack.isEnchanted()) return 0.0;
        double s = 0.0;
        for (var e : stack.getEnchantments().entrySet()) {
            int lvl = e.getIntValue();
            if (lvl <= 0) continue;
            s += enchantWeight(e.getKey(), slot, preferBlast) * lvl;
        }
        return s;
    }

    public static int materialRank(ItemStack s) {
        if (in(s, NETHERITE)) return 6;
        if (in(s, DIAMOND)) return 5;
        if (s.is(Items.TURTLE_HELMET)) return 4;
        if (in(s, IRON)) return 4;
        if (in(s, CHAINMAIL)) return 3;
        if (in(s, GOLD)) return 2;
        if (in(s, LEATHER)) return 1;
        return 0;
    }

    public static int remainingPct(ItemStack stack) {
        if (!stack.isDamageableItem() || stack.getMaxDamage() <= 0) return 100;
        return (int) Math.round(100.0 * (1.0 - (double) stack.getDamageValue() / stack.getMaxDamage()));
    }

    private static double enchantWeight(Holder<Enchantment> h, EquipmentSlot slot, boolean preferBlast) {
        if (h.is(Enchantments.PROTECTION))            return 8.0;
        if (h.is(Enchantments.BLAST_PROTECTION))      return preferBlast ? 10.0 : 7.0;
        if (h.is(Enchantments.PROJECTILE_PROTECTION)) return 6.0;
        if (h.is(Enchantments.FIRE_PROTECTION))       return 4.0;
        if (h.is(Enchantments.FEATHER_FALLING))       return slot == EquipmentSlot.FEET ? 6.0 : 0.0;
        if (h.is(Enchantments.MENDING))               return 4.0;
        if (h.is(Enchantments.THORNS))                return 3.0;
        if (h.is(Enchantments.UNBREAKING))            return 2.0;
        return 0.0;
    }

    private static int baseArmorPoints(ItemStack s, EquipmentSlot slot) {
        int i = slotIndex(slot);
        if (in(s, NETHERITE)) return AP_NETHERITE[i];
        if (in(s, DIAMOND))   return AP_DIAMOND[i];
        if (s.is(Items.TURTLE_HELMET)) return 2;
        if (in(s, IRON))      return AP_IRON[i];
        if (in(s, CHAINMAIL)) return AP_CHAIN[i];
        if (in(s, GOLD))      return AP_GOLD[i];
        if (in(s, LEATHER))   return AP_LEATHER[i];
        return 0;
    }

    private static double toughnessPoints(ItemStack s) {
        if (in(s, NETHERITE)) return 3.0;
        if (in(s, DIAMOND))   return 2.0;
        return 0.0;
    }

    private static int slotIndex(EquipmentSlot slot) {
        return switch (slot) { case HEAD -> 0; case CHEST -> 1; case LEGS -> 2; default -> 3; };
    }

    private static boolean in(ItemStack s, Item[] arr) {
        for (Item i : arr) if (s.is(i)) return true;
        return false;
    }
}
