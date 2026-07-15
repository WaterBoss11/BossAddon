package com.boss.pvp.util.pvp;

import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;

public final class DamageUtil {

    private static final EquipmentSlot[] ARMOR_SLOTS =
        { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
    private static final int MAX_EPF = 20;

    public static final double CRYSTAL_POWER = 6.0;

    private DamageUtil() {}

    public static double explosionExposure(Level level, Vec3 crystalPos, Entity entity) {
        return ServerExplosion.getSeenPercent(crystalPos, entity);
    }

    public static double rawExplosionDamage(double exposure, double distance, double power) {
        double range = 2.0 * power;
        if (distance >= range) return 0.0;
        double decay = 1.0 - (distance / range);
        double e = exposure * decay;
        if (e <= 0.0) return 0.0;
        return ((e * e + e) / 2.0) * 7.0 * range + 1.0;
    }

    public static int epfFromLevels(int protectionLevels, int blastProtectionLevels) {
        return Math.min(MAX_EPF, protectionLevels + blastProtectionLevels * 2);
    }

    public static int protectionEpf(LivingEntity entity) {
        int protection = 0;
        int blast = 0;
        for (EquipmentSlot slot : ARMOR_SLOTS) {
            ItemStack piece = entity.getItemBySlot(slot);
            if (piece.isEmpty()) continue;
            for (var entry : piece.getEnchantments().entrySet()) {
                int lvl = entry.getIntValue();
                if (lvl <= 0) continue;
                if (entry.getKey().is(Enchantments.BLAST_PROTECTION)) blast += lvl;
                else if (entry.getKey().is(Enchantments.PROTECTION)) protection += lvl;
            }
        }
        return epfFromLevels(protection, blast);
    }

    public static double effectiveDamage(LivingEntity entity, double raw, DamageSource source) {
        if (raw <= 0.0) return 0.0;
        double dmg = raw;

        if (entity instanceof Player && source != null && source.scalesWithDifficulty()) {
            switch (entity.level().getDifficulty()) {
                case PEACEFUL -> { return 0.0; }
                case EASY -> dmg = Math.min(dmg / 2.0 + 1.0, dmg);
                case HARD -> dmg = dmg * 1.5;
                default -> { }
            }
        }

        float armor = entity.getArmorValue();
        float toughness = (float) entity.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        dmg = CombatRules.getDamageAfterAbsorb(entity, (float) dmg, source, armor, toughness);

        if (entity.hasEffect(MobEffects.RESISTANCE)) {
            int amp = entity.getEffect(MobEffects.RESISTANCE).getAmplifier();
            int keep = 25 - (amp + 1) * 5;
            if (keep <= 0) return 0.0;
            dmg = Math.max(dmg * keep / 25.0, 0.0);
        }

        int epf = protectionEpf(entity);
        if (epf > 0) dmg = CombatRules.getDamageAfterMagicAbsorb((float) dmg, epf);

        return Math.max(0.0, dmg);
    }

    public static double crystalDamage(Level level, Vec3 crystalPos, LivingEntity entity, DamageSource source) {
        double distSq = entity.distanceToSqr(crystalPos);
        double range = 2.0 * CRYSTAL_POWER;
        if (distSq > range * range) return 0.0;
        double exposure = explosionExposure(level, crystalPos, entity);
        double raw = rawExplosionDamage(exposure, Math.sqrt(distSq), CRYSTAL_POWER);
        if (raw <= 0.0) return 0.0;
        return effectiveDamage(entity, raw, source);
    }

    public static boolean wouldSelfKill(double selfDamage, double health, double absorption) {
        return selfDamage >= health + absorption;
    }
}
