package com.boss.pvp.util.pvp;

import autismclient.util.AutismRotationUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

public final class RotationManager {

    public static final int PRIORITY_POT = 100;
    public static final int PRIORITY_PLACE = 80;
    public static final int PRIORITY_KILLAURA = 60;
    public static final int PRIORITY_LOW = 20;

    public record Request(float yaw, float pitch, int priority, boolean silent) {}

    private static volatile Request pending = null;
    private static volatile Request committed = null;

    private RotationManager() {}

    public static void submit(float yaw, float pitch, int priority, boolean silent) {
        Request p = pending;
        if (p == null || priority >= p.priority()) {
            pending = new Request(yaw, pitch, priority, silent);
        }
    }

    public static void endTickCommit() {
        committed = pending;
        pending = null;
    }

    public static Request committed() {
        return committed;
    }

    public static void reset() {
        pending = null;
        committed = null;
    }

    public static Packet<?> spoofOutgoing(Packet<?> packet) {
        Request req = committed;
        if (req == null || !req.silent() || !(packet instanceof ServerboundMovePlayerPacket move)) return packet;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.player == null) return packet;

        float realYaw = mc.player.getYRot();
        float realPitch = mc.player.getXRot();
        AutismRotationUtil.Rotation current = new AutismRotationUtil.Rotation(realYaw, realPitch);
        AutismRotationUtil.Rotation desired = new AutismRotationUtil.Rotation(req.yaw(), req.pitch());
        AutismRotationUtil.Rotation norm = AutismRotationUtil.normalizeToSensitivity(desired, current);
        float yaw = norm.yaw();
        float pitch = norm.pitch();

        boolean onGround = move.isOnGround();
        boolean horiz = move.horizontalCollision();
        if (move.hasPosition()) {
            return new ServerboundMovePlayerPacket.PosRot(
                move.getX(0.0), move.getY(0.0), move.getZ(0.0), yaw, pitch, onGround, horiz);
        }
        return new ServerboundMovePlayerPacket.Rot(yaw, pitch, onGround, horiz);
    }
}
