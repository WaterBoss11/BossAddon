package com.boss.pvp.module.render;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismBufferSource;
import autismclient.util.AutismWorldGeometry;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.AutismRenderTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class TrajectoryModule extends Module {

    private static final int COLOR_BLOCK  = 0xFF55FF55;
    private static final int COLOR_PLAYER = 0xFFFF5555;
    private static final int COLOR_FRIEND = 0xFFFFFF55;

    private boolean hitPlayer = false;
    private boolean hitFriend = false;

    // Per-tick simulation cache: re-simulate only when the game tick advances or the held item
    // changes, not on every rendered frame.
    private List<Vec3> cachedPath = null;
    private int cachedColor = COLOR_BLOCK;
    private long cachedTick = Long.MIN_VALUE;
    private Item cachedItem = null;

    public TrajectoryModule() {
        super(BossPvpAddon.ID + ":trajectory", "Trajectory",
            "Show the predicted flight path of projectiles before you throw them.");
        add(new BoolSetting("showArrow", "Bow / crossbow", true).group("Projectiles"));
        add(new BoolSetting("showPearl", "Ender pearl", true).group("Projectiles"));
        add(new BoolSetting("showSnowball", "Snowball", true).group("Projectiles"));
        add(new BoolSetting("showEgg", "Egg", false).group("Projectiles"));
        add(new BoolSetting("showFireball", "Fireball (fire charge)", false).group("Projectiles"));
        add(new BoolSetting("showPotion", "Splash / lingering potion", true).group("Projectiles"));
        add(new IntSetting("dotSpacing", "Dot spacing", 2, 1, 5, 1)
            .description("Draw a segment every N simulated ticks.").group("Display"));
        add(new IntSetting("maxTicks", "Max ticks", 100, 20, 200, 1)
            .description("How many ticks of flight to simulate.").group("Display"));
    }

    private record Spec(double speed, double gravity, double drag) {}

    @Override
    public void onDisable() {
        cachedPath = null;
        cachedItem = null;
        cachedTick = Long.MIN_VALUE;
        hitPlayer = false;
        hitFriend = false;
    }

    @Override
    public void onRenderLevel(float partialTick) {
        if (!isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;

        ItemStack held = throwable(p);
        if (held == null) { cachedPath = null; return; }
        Spec spec = specFor(held, p);
        if (spec == null) { cachedPath = null; return; }

        long now = mc.level.getGameTime();
        if (cachedPath == null || cachedTick != now || cachedItem != held.getItem()) {
            cachedPath = simulate(p, mc.level, spec);
            cachedItem = held.getItem();
            cachedTick = now;
            cachedColor = hitFriend ? COLOR_FRIEND : hitPlayer ? COLOR_PLAYER : COLOR_BLOCK;
        }
        List<Vec3> path = cachedPath;
        if (path.size() < 2) return;

        int color = cachedColor;

        Camera cam = mc.gameRenderer.mainCamera();
        Vec3 camPos = cam.position();
        PoseStack ps = new PoseStack();
        ps.mulPose(cam.getViewRotationMatrix(new Matrix4f()));
        PoseStack.Pose pose = ps.last();

        AutismBufferSource src = new AutismBufferSource();
        VertexConsumer vc = src.getBuffer(AutismRenderTypes.tracerEspLines());

        double cxp = camPos.x, cyp = camPos.y, czp = camPos.z;
        int spacing = Math.max(1, integer("dotSpacing"));
        for (int i = 0; i + 1 < path.size(); i += spacing) {
            int j = Math.min(i + spacing, path.size() - 1);
            Vec3 a = path.get(i);
            Vec3 b = path.get(j);
            AutismWorldGeometry.line(pose, vc, a.x - cxp, a.y - cyp, a.z - czp, b.x - cxp, b.y - cyp, b.z - czp, color, 2.0f);
        }

        Vec3 end = path.get(path.size() - 1);
        double ex = end.x - cxp, ey = end.y - cyp, ez = end.z - czp, s = 0.15;
        AutismWorldGeometry.line(pose, vc, ex - s, ey, ez, ex + s, ey, ez, color, 2.0f);
        AutismWorldGeometry.line(pose, vc, ex, ey - s, ez, ex, ey + s, ez, color, 2.0f);
        AutismWorldGeometry.line(pose, vc, ex, ey, ez - s, ex, ey, ez + s, color, 2.0f);

        src.uploadAndDraw();
    }

    private List<Vec3> simulate(LocalPlayer p, Level level, Spec spec) {
        hitPlayer = false;
        hitFriend = false;
        Vec3 pos = p.getEyePosition();
        Vec3 vel = p.getViewVector(1.0f).normalize().scale(spec.speed());
        int max = integer("maxTicks");
        List<Vec3> pts = new ArrayList<>();
        pts.add(pos);
        for (int i = 0; i < max; i++) {
            Vec3 next = pos.add(vel);
            if (!level.getWorldBorder().isWithinBounds(next.x, next.z)) { pts.add(next); return pts; }
            BlockHitResult bhr = level.clip(
                new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (bhr.getType() == HitResult.Type.BLOCK) {
                pts.add(bhr.getLocation());
                return pts;
            }
            Vec3 eh = entityHit(level, p, pos, next);
            if (eh != null) { pts.add(eh); return pts; }
            pos = next;
            pts.add(pos);
            vel = vel.scale(spec.drag()).subtract(0, spec.gravity(), 0);
        }
        return pts;
    }

    private Vec3 entityHit(Level level, LocalPlayer self, Vec3 from, Vec3 to) {
        AABB span = new AABB(from, to).inflate(1.0);
        double bestSq = Double.MAX_VALUE;
        Vec3 best = null;
        Entity bestEntity = null;
        for (Entity e : level.getEntities(self, span, e -> e != self && e.isAlive() && e.isPickable())) {
            Optional<Vec3> clip = e.getBoundingBox().inflate(0.15).clip(from, to);
            if (clip.isPresent()) {
                double d = from.distanceToSqr(clip.get());
                if (d < bestSq) { bestSq = d; best = clip.get(); bestEntity = e; }
            }
        }
        if (bestEntity instanceof Player pl) {
            hitPlayer = true;
            hitFriend = PvpUtil.isFriend(pl, BossPvpAddon.friends());
        }
        return best;
    }

    private Spec specFor(ItemStack held, LocalPlayer p) {
        Item item = held.getItem();
        if (item instanceof BowItem) return bool("showArrow") ? new Spec(3.0 * bowCharge(p), 0.05, 0.99) : null;
        if (item instanceof CrossbowItem) return bool("showArrow") ? new Spec(3.15, 0.05, 0.99) : null;
        if (item == Items.ENDER_PEARL) return bool("showPearl") ? new Spec(1.5, 0.03, 0.99) : null;
        if (item == Items.SNOWBALL) return bool("showSnowball") ? new Spec(1.5, 0.03, 0.99) : null;
        if (item == Items.EGG) return bool("showEgg") ? new Spec(1.5, 0.03, 0.99) : null;
        if (item == Items.SPLASH_POTION || item == Items.LINGERING_POTION)
            return bool("showPotion") ? new Spec(0.5, 0.05, 0.99) : null;
        if (item == Items.FIRE_CHARGE) return bool("showFireball") ? new Spec(1.0, 0.0, 1.0) : null;
        return null;
    }

    private ItemStack throwable(LocalPlayer p) {
        ItemStack main = p.getMainHandItem();
        if (isThrowable(main)) return main;
        ItemStack off = p.getOffhandItem();
        if (isThrowable(off)) return off;
        return null;
    }

    private boolean isThrowable(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        Item it = s.getItem();
        return it instanceof BowItem || it instanceof CrossbowItem || it == Items.ENDER_PEARL
            || it == Items.SNOWBALL || it == Items.EGG || it == Items.SPLASH_POTION
            || it == Items.LINGERING_POTION || it == Items.FIRE_CHARGE;
    }

    private double bowCharge(LocalPlayer p) {
        if (p.isUsingItem() && p.getUseItem().getItem() instanceof BowItem) {
            return BowItem.getPowerForTime(p.getTicksUsingItem());
        }
        return 1.0;
    }
}
