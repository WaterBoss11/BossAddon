package com.boss.pvp.util.pvp;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Adapted from AUTISM Client's GPL-3.0 rotation utilities, using native Minecraft only. */
public final class RotationUtil {
    private RotationUtil() {}

    public record Rotation(float yaw, float pitch) {}

    public static Rotation playerRotation(LocalPlayer player) {
        return new Rotation(player.getYRot(), player.getXRot());
    }

    public static Rotation lookingAt(Vec3 point, Vec3 from) {
        Vec3 delta = point.subtract(from);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
        return new Rotation(Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90.0f, 90.0f));
    }

    public static float angleDifference(float target, float current) {
        return Mth.wrapDegrees(target - current);
    }

    public static Rotation interpolate(Rotation current, Rotation target, float factor) {
        float t = Mth.clamp(factor, 0.0f, 1.0f);
        return new Rotation(current.yaw() + angleDifference(target.yaw(), current.yaw()) * t,
            Mth.clamp(current.pitch() + angleDifference(target.pitch(), current.pitch()) * t, -90.0f, 90.0f));
    }

    public static Rotation normalizeToSensitivity(Rotation rotation, Rotation current) {
        return Gcd.normalize(current, rotation);
    }

    public static void apply(LocalPlayer player, Rotation rotation, boolean syncPrevious) {
        if (player == null || rotation == null || !Float.isFinite(rotation.yaw()) || !Float.isFinite(rotation.pitch())) return;
        float yaw = Mth.wrapDegrees(rotation.yaw());
        float pitch = Mth.clamp(rotation.pitch(), -90.0f, 90.0f);
        player.yRotO = syncPrevious ? yaw : player.getYRot();
        player.xRotO = syncPrevious ? pitch : player.getXRot();
        if (syncPrevious) {
            player.setYHeadRot(yaw);
            player.yHeadRotO = yaw;
            player.setYBodyRot(yaw);
            player.yBodyRotO = yaw;
        }
        player.setYRot(yaw);
        player.setXRot(pitch);
    }
}
