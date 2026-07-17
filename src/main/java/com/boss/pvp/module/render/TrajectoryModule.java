package com.boss.pvp.module.render;

import com.boss.pvp.BossPvpAddon;
import com.boss.pvp.util.pvp.PvpUtil;

import autismclient.modules.Module;
import autismclient.api.module.*;
import autismclient.util.AutismWorldGeometry;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.rendertype.AutismRenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Projectile trajectory preview. The physics is a fork of LiquidBounce's TrajectoryInfoRenderer:
 * per-projectile constants (gravity/drag/velocity), a first-tick velocity correction for thrown items,
 * inherited player velocity, and 1.0-tick Euler integration that matches Minecraft's real projectile
 * motion. Simulating the physics at sub-tick would be wrong (drag/gravity are per-tick), so smoothness
 * comes from Catmull-Rom subdividing the RENDER path instead, drawn as a green->yellow->red gradient.
 */
public final class TrajectoryModule extends Module {

    private static final int COLOR_PLAYER = 0xFFFF3030; // impact marker: enemy player
    private static final int COLOR_FRIEND = 0xFFFFE23A; // impact marker: friend
    private static final int COLOR_MISS   = 0xFFFFFFFF; // impact marker: block / nothing

    private boolean hitPlayer = false;
    private boolean hitFriend = false;

    // Per-tick simulation cache: re-simulate only when the game tick advances or the held item changes.
    private List<Vec3> cachedPath = null;
    private int cachedMarker = COLOR_MISS;
    private long cachedTick = Long.MIN_VALUE;
    private Item cachedItem = null;

    public TrajectoryModule() {
        super(BossPvpAddon.ID + ":trajectory", "Trajectory",
            "Show the predicted flight path of projectiles before you throw them.");
        add(new BoolSetting("showArrow", "Bow / crossbow", true).group("Projectiles"));
        add(new BoolSetting("showTrident", "Trident", true).group("Projectiles"));
        add(new BoolSetting("showPearl", "Ender pearl", true).group("Projectiles"));
        add(new BoolSetting("showSnowball", "Snowball", true).group("Projectiles"));
        add(new BoolSetting("showEgg", "Egg", false).group("Projectiles"));
        add(new BoolSetting("showFireball", "Fireball (fire charge)", false).group("Projectiles"));
        add(new BoolSetting("showPotion", "Splash / lingering potion", true).group("Projectiles"));

        add(new IntSetting("maxTicks", "Max ticks", 200, 20, 200, 1)
            .description("How many ticks of flight to simulate.").group("Display"));
        add(new IntSetting("smoothness", "Smoothness", 10, 1, 16, 1)
            .description("Render subdivisions per simulated tick (Catmull-Rom curve). 1 = raw per-tick line.").group("Display"));
        add(new DoubleSetting("width", "Line width", 2.5, 1.0, 6.0, 0.5).group("Display"));

        // World-space geometry must be drawn from Fabric's level-render event (correct pose + buffer).
        LevelRenderEvents.COLLECT_SUBMITS.register(this::renderTrajectory);
    }

    /** Per-projectile physics (mirrors LiquidBounce's TrajectoryInfo). */
    private record Traj(double gravity, double hitboxRadius, double initialVelocity, double drag,
                        double dragInWater, float roll, boolean copiesPlayerVelocity, boolean initialTickCorrection) {}

    // LiquidBounce GENERIC: thrown items (pearl/snowball/egg).
    private static Traj generic(double velocity) {
        return new Traj(0.03, 0.25, velocity, 0.99, 0.8, 0f, true, true);
    }

    @Override
    public void onDisable() {
        cachedPath = null;
        cachedItem = null;
        cachedTick = Long.MIN_VALUE;
        hitPlayer = false;
        hitFriend = false;
    }

    private void renderTrajectory(LevelRenderContext context) {
        if (!isEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;

        ItemStack held = throwable(p);
        if (held == null) { cachedPath = null; return; }
        Traj tr = trajFor(held, p);
        if (tr == null) { cachedPath = null; return; }

        long now = mc.level.getGameTime();
        if (cachedPath == null || cachedTick != now || cachedItem != held.getItem()) {
            List<Vec3> raw = simulate(p, mc.level, tr);
            cachedPath = smooth(raw, Math.max(1, integer("smoothness")));
            cachedItem = held.getItem();
            cachedTick = now;
            cachedMarker = hitFriend ? COLOR_FRIEND : hitPlayer ? COLOR_PLAYER : COLOR_MISS;
        }
        final List<Vec3> pts = cachedPath;
        if (pts == null || pts.size() < 2) return;

        final int marker = cachedMarker;
        final float width = (float) decimal("width");
        Vec3 camPos = mc.gameRenderer.mainCamera().position();
        final double cx = camPos.x, cy = camPos.y, cz = camPos.z;

        context.submitNodeCollector().submitCustomGeometry(context.poseStack(),
            AutismRenderTypes.tracerEspLines(),
            (pose, vc) -> {
                int n = pts.size();
                for (int i = 0; i + 1 < n; i++) {
                    Vec3 a = pts.get(i), b = pts.get(i + 1);
                    int col = gradient((double) i / (n - 1));
                    AutismWorldGeometry.line(pose, vc,
                        a.x - cx, a.y - cy, a.z - cz, b.x - cx, b.y - cy, b.z - cz, col, width);
                }
                // Impact marker: a larger 3-axis cross at the landing point.
                Vec3 end = pts.get(n - 1);
                double ex = end.x - cx, ey = end.y - cy, ez = end.z - cz, s = 0.35;
                AutismWorldGeometry.line(pose, vc, ex - s, ey, ez, ex + s, ey, ez, marker, width);
                AutismWorldGeometry.line(pose, vc, ex, ey - s, ez, ex, ey + s, ez, marker, width);
                AutismWorldGeometry.line(pose, vc, ex, ey, ez - s, ex, ey, ez + s, marker, width);
            });
    }

    // ---- physics (LiquidBounce fork) -----------------------------------------------------------

    private List<Vec3> simulate(LocalPlayer p, Level level, Traj tr) {
        hitPlayer = false;
        hitFriend = false;

        // Direction from yaw/pitch (roll shifts pitch for potions), scaled to the initial velocity.
        double yawR = Math.toRadians(p.getYRot());
        double pitchR = Math.toRadians(p.getXRot());
        double pitchRollR = Math.toRadians(p.getXRot() + tr.roll());
        Vec3 dir = new Vec3(
            -Math.sin(yawR) * Math.cos(pitchR),
            -Math.sin(pitchRollR),
            Math.cos(yawR) * Math.cos(pitchR));
        Vec3 vel = dir.normalize().scale(tr.initialVelocity());

        // Thrown/shot projectiles inherit the shooter's momentum (y only while airborne).
        if (tr.copiesPlayerVelocity()) {
            Vec3 dm = p.getDeltaMovement();
            vel = vel.add(dm.x, p.onGround() ? 0.0 : dm.y, dm.z);
        }

        // Real spawn position: (x, eyeY - 0.1, z), matching vanilla projectile spawn.
        Vec3 pos = new Vec3(p.getX(), p.getEyeY() - 0.10000000149011612, p.getZ());
        int max = integer("maxTicks");

        List<Vec3> pts = new ArrayList<>(max + 2);
        pts.add(pos);

        // Thrown items get one physics tick on spawn before the first move.
        if (tr.initialTickCorrection()) vel = tickVelocity(vel, pos, level, tr);

        for (int i = 0; i < max; i++) {
            if (pos.y < level.getMinY()) break;
            Vec3 next = pos.add(vel);

            BlockHitResult bhr = level.clip(new ClipContext(
                pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (bhr.getType() == HitResult.Type.BLOCK) { pts.add(bhr.getLocation()); return pts; }

            Vec3 eh = entityHit(level, p, pos, next, tr.hitboxRadius());
            if (eh != null) { pts.add(eh); return pts; }

            pos = next;
            pts.add(pos);
            vel = tickVelocity(vel, pos, level, tr);
        }
        return pts;
    }

    /** velocity *= drag (water-aware); velocity.y -= gravity. */
    private Vec3 tickVelocity(Vec3 vel, Vec3 pos, Level level, Traj tr) {
        boolean inFluid = !level.getBlockState(BlockPos.containing(pos)).getFluidState().isEmpty();
        double drag = inFluid ? tr.dragInWater() : tr.drag();
        return vel.scale(drag).subtract(0.0, tr.gravity(), 0.0);
    }

    private Vec3 entityHit(Level level, LocalPlayer self, Vec3 from, Vec3 to, double radius) {
        AABB span = new AABB(from, to).inflate(1.0);
        double bestSq = Double.MAX_VALUE;
        Vec3 best = null;
        Entity bestEntity = null;
        for (Entity e : level.getEntities(self, span, e -> e != self && e.isAlive() && e.isPickable())) {
            Optional<Vec3> clip = e.getBoundingBox().inflate(radius).clip(from, to);
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

    // ---- render smoothing + color --------------------------------------------------------------

    /** Catmull-Rom subdivide the per-tick points into a smooth curve that passes through each point. */
    private static List<Vec3> smooth(List<Vec3> pts, int subdiv) {
        int n = pts.size();
        if (n < 3 || subdiv <= 1) return pts;
        List<Vec3> out = new ArrayList<>(n * subdiv);
        for (int i = 0; i < n - 1; i++) {
            Vec3 p0 = pts.get(Math.max(0, i - 1));
            Vec3 p1 = pts.get(i);
            Vec3 p2 = pts.get(i + 1);
            Vec3 p3 = pts.get(Math.min(n - 1, i + 2));
            for (int s = 0; s < subdiv; s++) {
                out.add(catmull(p0, p1, p2, p3, (double) s / subdiv));
            }
        }
        out.add(pts.get(n - 1)); // exact landing point
        return out;
    }

    private static Vec3 catmull(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double t) {
        double t2 = t * t, t3 = t2 * t;
        return new Vec3(
            0.5 * ((2 * p1.x) + (-p0.x + p2.x) * t + (2 * p0.x - 5 * p1.x + 4 * p2.x - p3.x) * t2 + (-p0.x + 3 * p1.x - 3 * p2.x + p3.x) * t3),
            0.5 * ((2 * p1.y) + (-p0.y + p2.y) * t + (2 * p0.y - 5 * p1.y + 4 * p2.y - p3.y) * t2 + (-p0.y + 3 * p1.y - 3 * p2.y + p3.y) * t3),
            0.5 * ((2 * p1.z) + (-p0.z + p2.z) * t + (2 * p0.z - 5 * p1.z + 4 * p2.z - p3.z) * t2 + (-p0.z + 3 * p1.z - 3 * p2.z + p3.z) * t3));
    }

    /** Smooth green -> yellow -> red gradient by fraction along the path. */
    private static int gradient(double f) {
        f = Math.max(0.0, Math.min(1.0, f));
        int r, g;
        if (f < 0.5) { double t = f / 0.5;          r = (int) (85 + t * (255 - 85)); g = 255; }
        else         { double t = (f - 0.5) / 0.5;  r = 255; g = (int) (255 - t * (255 - 85)); }
        return 0xFF000000 | (r << 16) | (g << 8) | 85;
    }

    // ---- item -> physics mapping (LiquidBounce constants) --------------------------------------

    private Traj trajFor(ItemStack held, LocalPlayer p) {
        Item item = held.getItem();
        if (item instanceof BowItem) {
            if (!bool("showArrow")) return null;
            double power = bowCharge(p);
            if (power < 0.1) return null;
            return new Traj(0.05, 0.5, 3.0 * power, 0.99, 0.99, 0f, true, false);
        }
        if (item instanceof CrossbowItem)
            return bool("showArrow") ? new Traj(0.05, 0.5, 3.15, 0.99, 0.99, 0f, false, false) : null;
        if (item instanceof TridentItem)
            return bool("showTrident") ? new Traj(0.05, 0.5, 2.5, 0.99, 0.99, 0f, true, false) : null;
        if (item == Items.ENDER_PEARL) return bool("showPearl") ? generic(1.5) : null;
        if (item == Items.SNOWBALL) return bool("showSnowball") ? generic(1.5) : null;
        if (item == Items.EGG) return bool("showEgg") ? generic(1.5) : null;
        if (item == Items.SPLASH_POTION || item == Items.LINGERING_POTION)
            return bool("showPotion") ? new Traj(0.05, 0.25, 0.5, 0.99, 0.8, -20f, true, true) : null;
        if (item == Items.FIRE_CHARGE)
            return bool("showFireball") ? new Traj(0.0, 1.0, 1.5, 0.99, 0.99, 0f, true, true) : null;
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
        return it instanceof BowItem || it instanceof CrossbowItem || it instanceof TridentItem
            || it == Items.ENDER_PEARL || it == Items.SNOWBALL || it == Items.EGG
            || it == Items.SPLASH_POTION || it == Items.LINGERING_POTION || it == Items.FIRE_CHARGE;
    }

    private double bowCharge(LocalPlayer p) {
        if (p.isUsingItem() && p.getUseItem().getItem() instanceof BowItem) {
            return BowItem.getPowerForTime(p.getTicksUsingItem());
        }
        return 1.0; // full-pull preview when not drawing
    }
}
