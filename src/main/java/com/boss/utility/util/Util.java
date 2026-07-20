package com.boss.utility.util;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/** Small shared helpers for BossUtility modules. */
public final class Util {

    private Util() {}

    /** True if the entity's type id (namespaced or short) matches any entry in the id list. */
    public static boolean matchesType(Entity e, List<String> ids) {
        if (ids == null || ids.isEmpty()) return false;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().toLowerCase(Locale.ROOT);
        String shortId = id.substring(id.indexOf(':') + 1);
        for (String entry : ids) {
            if (entry == null) continue;
            String v = entry.trim().toLowerCase(Locale.ROOT);
            if (v.equals(id) || v.equals(shortId)) return true;
        }
        return false;
    }

    /** Snaps the player's rotation to face a world point (used before interactions). */
    public static void face(LocalPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x, dy = target.y - eye.y, dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.setYHeadRot(yaw);
    }
}
