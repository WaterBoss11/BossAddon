package com.boss.pvp.test;

import autismclient.modules.Module;
import autismclient.util.AutismConfig;
import com.boss.pvp.module.combat.AutoWeaponModule;
import com.boss.pvp.util.CombatManager;
import com.boss.pvp.util.input.HeldSlotManager;
import com.boss.pvp.util.pvp.PvpUtil;
import com.google.gson.JsonParser;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

/** Real outbound packet path against the fixture's server player; never invokes the hook directly. */
@SuppressWarnings("UnstableApiUsage")
final class BossQaCombatPackets implements AutoCloseable {
    private final ClientGameTestContext context;
    private final BossQaNativeFixtures fixtures;
    private final UUID targetId;
    private final Saved saved;
    private boolean closed;

    private record Saved(AutoWeaponModule weapon, AutismConfig config, AutismConfig.ModuleState teams,
                         boolean weaponEnabled, boolean teamsEnabled, String friends,
                         String switchBack, String onlyHotbar) { }

    BossQaCombatPackets(ClientGameTestContext context, BossQaNativeFixtures fixtures, UUID targetId) {
        this.context = context;
        this.fixtures = fixtures;
        this.targetId = targetId;
        this.saved = context.computeOnClient(client -> {
            Module module = BossQaModules.require("autoweapon");
            BossQaIsolation.require(module instanceof AutoWeaponModule, "Actual registered AutoWeapon has the wrong type");
            AutismConfig config = AutismConfig.getGlobal();
            BossQaIsolation.require(config != null && config.modules != null, "Native host config is absent");
            AutismConfig.ModuleState teams = config.modules.get("teams");
            BossQaIsolation.require(teams != null && teams.settings != null, "Native Teams configuration is absent");
            return new Saved((AutoWeaponModule) module, config, teams, module.isEnabled(), teams.enabled,
                    teams.settings.get("friends"), module.value("switchBack"), module.value("onlyHotbar"));
        });
    }

    private void prepare() {
        fixtures.resetCombatActors(targetId);
        fixtures.setLocalLoadout(Map.of(0, new ItemStack(Items.STICK),
                5, new ItemStack(Items.NETHERITE_SWORD)), 0);
        context.runOnClient(client -> {
            saved.teams().enabled = false;
            saved.weapon().setValueTransient("switchBack", "true");
            saved.weapon().setValueTransient("onlyHotbar", "true");
            saved.weapon().setEnabled(true);
            BossQaIsolation.require(saved.weapon().isEnabled() && nativeTeamsDisabled(), "Combat fixture did not enable only AutoWeapon");
            BossQaIsolation.require(!PvpUtil.isProtectedPlayer(client, visible(client)), "Positive fixture target is unexpectedly protected");
        });
        fixtures.awaitClient("real weapon cooldown and neutral slot", client -> client.player.getInventory().getSelectedSlot() == 0
                && !HeldSlotManager.holds(saved.weapon()) && saved.weapon().chargeReadyForBestWeapon(visible(client))
                && System.currentTimeMillis() > CombatManager.lastAttackMs());
    }

    void proveRawAttack() {
        prepare();
        float before = fixtures.targetHealth(targetId);
        try {
            context.runOnClient(client -> {
                Player target = visible(client);
                long previous = CombatManager.lastAttackMs();
                // Deliberately bypass gameMode.attack / Fabric AttackEntityCallback, exactly as native Packet mode does.
                client.getConnection().send(new ServerboundAttackPacket(target.getId()));
                BossQaIsolation.require(client.player.getInventory().getSelectedSlot() == 5
                        && HeldSlotManager.holds(saved.weapon()), "Raw packet did not synchronously acquire/select the real best weapon");
                BossQaIsolation.require(CombatManager.currentTarget(client) == target
                        && CombatManager.lastAttackMs() > previous, "Raw packet did not update shared CombatManager observation");
            });
            fixtures.awaitTargetDamage(targetId, before);
            float after = fixtures.targetHealth(targetId);
            System.out.println("[BOSS-QA] raw-packet authoritative damage evidence: before=" + before + " after=" + after
                    + " delta=" + (before - after) + "; synchronous best-slot/lease/shared-target assertions already passed");
            fixtures.awaitClient("AutoWeapon restores slot and releases lease", client ->
                    client.player.getInventory().getSelectedSlot() == 0 && !HeldSlotManager.holds(saved.weapon()));
            fixtures.awaitSelectedSlot(0);
            System.out.println("[BOSS-QA] raw-packet natural restoration confirmed: client/server slot=0, weapon lease released");
            BossQaIsolation.require(after > 0 && before - after > 1.0f,
                    "Raw packet did not produce surviving server-confirmed weapon damage beyond the stick control: before="
                            + before + " after=" + after + " delta=" + (before - after));
            System.out.println("[BOSS-QA] raw-packet server damage: before=" + before + " after=" + after
                    + "; observed best slot=5, real client/server restored slot=0");
        } finally {
            context.runOnClient(client -> saved.weapon().setEnabled(false));
        }
    }

    void proveStoredFriendVeto() {
        prepare();
        float before = fixtures.targetHealth(targetId);
        context.runOnClient(client -> {
            saved.teams().enabled = false;
            saved.teams().settings.put("friends", visible(client).getGameProfile().name());
            saved.config().save();
            BossQaIsolation.require(nativeTeamsDisabled() && PvpUtil.isProtectedPlayer(client, visible(client)),
                    "Stored friend is not protected independently of native Teams enablement");
        });
        fixtures.awaitClient("actual isolated config persists disabled Teams and friend", client -> {
            try {
                var root = JsonParser.parseString(Files.readString(BossQaIsolation.profile().resolve("config/autism/config.json"))).getAsJsonObject();
                var teams = root.getAsJsonObject("modules").getAsJsonObject("teams");
                return !teams.get("enabled").getAsBoolean()
                        && visible(client).getGameProfile().name().equals(teams.getAsJsonObject("settings").get("friends").getAsString());
            } catch (IOException failure) {
                throw new AssertionError("Cannot read the native isolated friend configuration", failure);
            }
        });
        try {
            context.runOnClient(client -> {
                long previous = CombatManager.lastAttackMs();
                client.getConnection().send(new ServerboundAttackPacket(visible(client).getId()));
                BossQaIsolation.require(CombatManager.lastAttackMs() == previous,
                        "Stored-friend raw packet was recorded as a permitted attack");
                BossQaIsolation.require(nativeTeamsDisabled() && client.player.getInventory().getSelectedSlot() == 0
                        && !HeldSlotManager.holds(saved.weapon()), "Friend veto changed Teams, borrowed a slot or retained a lease");
                // Ordered native packet barrier: the server must process this AFTER any forwarded attack.
                client.player.getInventory().setSelectedSlot(2);
                client.getConnection().send(new ServerboundSetCarriedItemPacket(2));
            });
            fixtures.awaitSelectedSlot(2);
            context.waitTicks(5);
            float after = fixtures.targetHealth(targetId);
            BossQaIsolation.require(after == before, "Stored-friend raw packet reached the server and damaged its real player");
            context.runOnClient(client -> BossQaIsolation.require(nativeTeamsDisabled()
                    && !HeldSlotManager.holds(saved.weapon()), "Native Teams silently enabled or the friend veto leaked a lease"));
            System.out.println("[BOSS-QA] stored-friend raw packet veto: server health before=" + before + " after=" + after
                    + "; native Teams remains disabled; ordered server packet barrier acknowledged");
        } finally {
            context.runOnClient(client -> {
                saved.weapon().setEnabled(false);
                client.player.getInventory().setSelectedSlot(0);
                client.getConnection().send(new ServerboundSetCarriedItemPacket(0));
            });
            fixtures.awaitSelectedSlot(0);
        }
    }

    private Player visible(Minecraft client) {
        Player target = client.level == null ? null : client.level.getPlayerByUUID(targetId);
        BossQaIsolation.require(target != null && target.isAlive() && client.player != null
                && client.player.distanceTo(target) < 3, "Native raw-packet target is not replicated/alive/in real reach");
        return target;
    }

    private boolean nativeTeamsDisabled() {
        AutismConfig current = AutismConfig.getGlobal();
        return current == saved.config() && current.modules.get("teams") != null
                && !current.modules.get("teams").enabled && !PvpUtil.hostEnabled("teams");
    }

    @Override
    public void close() {
        if (closed) return;
        try {
            context.runOnClient(client -> {
                try {
                    saved.weapon().setEnabled(false);
                } finally {
                    try {
                        saved.teams().enabled = saved.teamsEnabled();
                        if (saved.friends() == null) saved.teams().settings.remove("friends");
                        else saved.teams().settings.put("friends", saved.friends());
                        saved.config().save();
                    } finally {
                        saved.weapon().setValueTransient("switchBack", saved.switchBack());
                        saved.weapon().setValueTransient("onlyHotbar", saved.onlyHotbar());
                        saved.weapon().setEnabled(saved.weaponEnabled());
                    }
                }
            });
        } finally {
            closed = true;
        }
    }
}
