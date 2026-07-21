package com.boss.pvp;

import com.boss.pvp.relay.RelayConfig;
import com.boss.pvp.relay.RelayManager;
import com.boss.pvp.util.AddonHalves;
import com.boss.pvp.util.MenuMode;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.fabricmc.api.ClientModInitializer;

/**
 * The single command entry point for the whole addon: {@code ?bossaddon} and its subcommands. Everything is
 * reached through this one root, so a user types {@code ?bossaddon help} and can discover every feature.
 *
 * <pre>
 *   ?bossaddon                      status overview
 *   ?bossaddon help [topic]         list commands, or details on one
 *   ?bossaddon pvp on|off           toggle the PvP half
 *   ?bossaddon utility on|off       toggle the Utility half
 *   ?bossaddon menu simple|advanced settings menu detail level
 *   ?bossaddon chat ...             BossChat controls (scopes, messaging, DMs, enable/disable)
 *   ?bossaddon party ...            BossChat party (membership, invite, party chat, warp)
 * </pre>
 *
 * <p><b>Prefix.</b> The addon deliberately uses {@code "?"} rather than {@code "/"}. {@code "/"} is a real
 * Minecraft command character sent to the server; {@code "?"} is not, so it can't reach the server and won't
 * collide with server commands, AUTISM's command prefix (one of {@code . % - _ * # @ & =}), or its panic
 * fallbacks ({@code . , ; : - # ! ~}). Because {@code "?"} would otherwise be ordinary chat, the commands are
 * NOT registered with Fabric's {@code "/"} dispatcher — they live in the private {@link #DISPATCHER} below and
 * are fed in by {@code ChatScreenMixin}, which intercepts a leading {@code "?bossaddon"} token before the line
 * is sent as chat (see {@link #isBossCommand}).
 */
public final class BossAddonInit implements ClientModInitializer {

    /** The command prefix. Not "/" — see the class javadoc. */
    public static final String PREFIX = "?";
    /** The single root literal; a chat line is a command only when it is exactly this token after the prefix. */
    public static final String ROOT = "bossaddon";

    // Private Brigadier dispatcher (the tree is fed "bossaddon ..." strings, without the "?" prefix). The
    // source is unused — every handler ignores it — so a throwaway Object is fine.
    private static final CommandDispatcher<Object> DISPATCHER = new CommandDispatcher<>();
    private static final Object SOURCE = new Object();

    /** One row of the help listing: the subcommand and a short, plain-language description (5-8 words). */
    private record Cmd(String usage, String desc) {}

    // Top-level subcommands, in the order shown by ?bossaddon help.
    private static final Cmd[] HELP = {
        new Cmd("help [topic]",        "List commands, or details on one"),
        new Cmd("pvp on|off",          "Turn the PvP half on or off"),
        new Cmd("utility on|off",      "Turn the Utility half on or off"),
        new Cmd("menu simple|advanced","Choose how much the menu shows"),
        new Cmd("chat ...",            "BossChat: talk across servers"),
        new Cmd("party ...",           "Make and manage a chat party"),
    };

    @Override
    public void onInitializeClient() {
        DISPATCHER.register(lit(ROOT)
            .then(half(AddonHalves.PVP))
            .then(half(AddonHalves.UTILITY))
            .then(menuSubtree("menu"))
            .then(chatSubtree("chat"))
            .then(partySubtree("party"))
            .then(helpSubtree("help"))
            .executes(ctx -> overview()));
    }

    // ---- ? interception entry points (called from ChatScreenMixin) -----------------------------------

    /**
     * True when a chat line is a BossAddon command: it starts with {@code "?bossaddon"} where {@code bossaddon}
     * is a complete first token (followed by a space or the end of the line). Any other {@code "?..."} — a lone
     * {@code "?"}, {@code "?hi"}, {@code "?bossaddonx"}, a server chat-plugin command — is NOT a command and is
     * left to be sent as normal chat, so genuine {@code "?"} messages are never eaten.
     */
    public static boolean isBossCommand(String message) {
        if (message == null || !message.startsWith(PREFIX)) return false;
        String rest = message.substring(PREFIX.length());
        return rest.equals(ROOT) || rest.startsWith(ROOT + " ");
    }

    /** Run a command line (already confirmed by {@link #isBossCommand}), given WITHOUT the leading "?". */
    public static void dispatch(String withoutPrefix) {
        try {
            DISPATCHER.execute(withoutPrefix.trim(), SOURCE);
        } catch (CommandSyntaxException e) {
            msg("§7Unknown BossAddon command §8· §7try §f?bossaddon help§7.");
        } catch (Throwable t) {
            msg("§7BossAddon command error: " + t.getMessage());
        }
    }

    // ---- brigadier builder shorthands (source type is unused Object) ---------------------------------

    private static LiteralArgumentBuilder<Object> lit(String name) {
        return LiteralArgumentBuilder.literal(name);
    }

    private static RequiredArgumentBuilder<Object, String> strArg(String name, ArgumentType<String> type) {
        return RequiredArgumentBuilder.argument(name, type);
    }

    // ---- halves --------------------------------------------------------------------------------------

    private static LiteralArgumentBuilder<Object> half(String name) {
        return lit(name)
            .then(lit("on").executes(ctx -> applyHalf(name, true)))
            .then(lit("off").executes(ctx -> applyHalf(name, false)))
            .executes(ctx -> { msg("[BossAddon] " + AddonHalves.status()); return 1; });
    }

    private static int applyHalf(String half, boolean on) {
        String result = AddonHalves.setHalf(half, on);
        msg("[BossAddon] " + (result == null ? "Unknown half: " + half : result));
        return 1;
    }

    // ---- menu mode -----------------------------------------------------------------------------------

    private static LiteralArgumentBuilder<Object> menuSubtree(String name) {
        return lit(name)
            .then(lit("simple").executes(ctx -> setMenuMode(false)))
            .then(lit("advanced").executes(ctx -> setMenuMode(true)))
            .executes(ctx -> setMenuMode(!MenuMode.advanced()));
    }

    private static int setMenuMode(boolean advanced) {
        MenuMode.setAdvanced(advanced);
        msg("[Boss's PVP] Menu mode: "
            + (advanced ? "Advanced (all settings shown)" : "Simple (essential settings only)"));
        return 1;
    }

    // ---- BossChat / party subtrees -------------------------------------------------------------------

    /** ?bossaddon chat: scope toggles, messaging, DMs, reconnect, enable/disable, bare = status. */
    private static LiteralArgumentBuilder<Object> chatSubtree(String name) {
        return lit(name)
            .then(lit("off").executes(ctx -> setRelayMode(RelayManager.Mode.OFF)))
            .then(lit("global").executes(ctx -> setRelayMode(RelayManager.Mode.GLOBAL)))
            .then(lit("server").executes(ctx -> setRelayMode(RelayManager.Mode.SERVER)))
            .then(lit("reconnect").executes(ctx -> relayReconnect()))
            // Full opt-out (distinct from the OFF scope: OFF still receives; disable disconnects entirely).
            // These deliberately bypass relayGate() so "enable" works while the feature is currently disabled.
            .then(lit("disable").executes(ctx -> relayDisable()))
            .then(lit("enable").executes(ctx -> relayEnable()))
            .then(lit("g")
                .then(strArg("message", StringArgumentType.greedyString())
                    .executes(ctx -> relaySend("global", null, StringArgumentType.getString(ctx, "message")))))
            .then(lit("s")
                .then(strArg("message", StringArgumentType.greedyString())
                    .executes(ctx -> relaySend("server", null, StringArgumentType.getString(ctx, "message")))))
            .then(lit("dm")
                .then(strArg("user", StringArgumentType.word())
                    .then(strArg("message", StringArgumentType.greedyString())
                        .executes(ctx -> relaySend("dm", StringArgumentType.getString(ctx, "user"),
                            StringArgumentType.getString(ctx, "message"))))))
            .executes(ctx -> relayStatus());
    }

    /** ?bossaddon party: membership actions, invite by name, "msg" for party chat, warp, bare = list. */
    private static LiteralArgumentBuilder<Object> partySubtree(String name) {
        return lit(name)
            .then(lit("accept").executes(ctx -> relayParty("accept")))
            .then(lit("decline").executes(ctx -> relayParty("decline")))
            .then(lit("leave").executes(ctx -> relayParty("leave")))
            .then(lit("list").executes(ctx -> relayParty("list")))
            .then(lit("msg")
                .then(strArg("message", StringArgumentType.greedyString())
                    .executes(ctx -> relaySend("party", null, StringArgumentType.getString(ctx, "message")))))
            // Warp: propose your current server to a member (or the whole party); the recipient must accept.
            // "warp" bare = propose to the party; "warp <user>" = propose to one member; accept/decline respond.
            .then(lit("warp")
                .then(lit("accept").executes(ctx -> relayWarpAccept()))
                .then(lit("decline").executes(ctx -> relayWarpDecline()))
                .then(strArg("user", StringArgumentType.word())
                    .executes(ctx -> relayWarpPropose(StringArgumentType.getString(ctx, "user"))))
                .executes(ctx -> relayWarpPropose(null)))
            // (Brigadier matches the literals above before the <user> argument, so a user literally named e.g.
            // "list" can't be invited by name — an acceptable v1 edge case.)
            .then(strArg("user", StringArgumentType.word())
                .executes(ctx -> relayPartyInvite(StringArgumentType.getString(ctx, "user"))))
            .executes(ctx -> relayParty("list"));
    }

    // ---- relay handlers ------------------------------------------------------------------------------

    private static int setRelayMode(RelayManager.Mode mode) {
        if (relayGate()) return 1;
        RelayManager.get().setMode(mode);
        msg(com.boss.pvp.relay.BossChatFormat.scopeChanged(
            mode.name().toLowerCase(), mode == RelayManager.Mode.OFF));
        return 1;
    }

    private static int relaySend(String scope, String user, String message) {
        if (relayGate()) return 1;
        switch (scope) {
            case "server" -> RelayManager.get().sendServer(message);
            case "party" -> RelayManager.get().sendParty(message);
            case "dm" -> RelayManager.get().sendDm(user, message);
            default -> RelayManager.get().sendGlobal(message);
        }
        return 1;
    }

    private static int relayParty(String action) {
        if (relayGate()) return 1;
        RelayManager r = RelayManager.get();
        switch (action) {
            case "accept" -> r.partyAccept();
            case "decline" -> r.partyDecline();
            case "leave" -> r.partyLeave();
            case "list" -> r.partyList();
        }
        return 1;
    }

    private static int relayPartyInvite(String user) {
        if (relayGate()) return 1;
        RelayManager.get().partyInvite(user);
        return 1;
    }

    /** Propose a warp to your current server: to one member (user != null) or the whole party (null). */
    private static int relayWarpPropose(String user) {
        if (relayGate()) return 1;
        RelayManager.get().warpPropose(user);
        return 1;
    }

    private static int relayWarpAccept() {
        if (relayGate()) return 1;
        RelayManager.get().warpAccept();
        return 1;
    }

    private static int relayWarpDecline() {
        if (relayGate()) return 1;
        RelayManager.get().warpDecline();
        return 1;
    }

    private static int relayReconnect() {
        if (relayGate()) return 1;
        msg(com.boss.pvp.relay.BossChatFormat.reconnecting());
        RelayManager.get().connect();
        return 1;
    }

    private static int relayStatus() {
        if (relayGate()) return 1;
        RelayManager r = RelayManager.get();
        msg(com.boss.pvp.relay.BossChatFormat.statusReport(r.status(), r.mode().name().toLowerCase()));
        return 1;
    }

    /** Full opt-out: persist the flag first (so the gate is off), then tear the connection down. */
    private static int relayDisable() {
        RelayConfig.setEnabled(false);
        RelayManager.get().disconnect();
        msg(com.boss.pvp.relay.BossChatFormat.disabled());
        return 1;
    }

    /** Opt back in: persist enabled, then connect (only meaningful if a relay URL is configured). */
    private static int relayEnable() {
        RelayConfig.setEnabled(true);
        if (RelayConfig.url() == null) {
            msg(com.boss.pvp.relay.BossChatFormat.notEnabled());
            return 1;
        }
        msg(com.boss.pvp.relay.BossChatFormat.reconnecting());
        RelayManager.get().connect();
        return 1;
    }

    /** True (and prints a hint) when the relay isn't configured — BossChat is inert on this install. */
    private static boolean relayGate() {
        if (RelayConfig.isConfigured()) return false;
        msg(com.boss.pvp.relay.BossChatFormat.notEnabled());
        return true;
    }

    // ---- help ----------------------------------------------------------------------------------------

    private static LiteralArgumentBuilder<Object> helpSubtree(String name) {
        return lit(name)
            .then(strArg("topic", StringArgumentType.word())
                .executes(ctx -> helpTopic(StringArgumentType.getString(ctx, "topic"))))
            .executes(ctx -> helpList());
    }

    /** Bare ?bossaddon: halves status + a pointer to help. */
    private static int overview() {
        msg("§6[BossAddon]§r " + AddonHalves.status());
        msg("§7Type §f?bossaddon help§7 to see every command.");
        return 1;
    }

    /** ?bossaddon help: one line per subcommand. */
    private static int helpList() {
        msg("§6[BossAddon] Commands§r §7(?bossaddon help <topic> for detail)");
        for (Cmd c : HELP) {
            msg("  §f?bossaddon " + c.usage() + "§8 — §7" + c.desc());
        }
        return 1;
    }

    /** ?bossaddon help <topic>: a few lines of detail for one subcommand. */
    private static int helpTopic(String topic) {
        switch (topic.toLowerCase()) {
            case "pvp" -> {
                msg("§6?bossaddon pvp on|off§r");
                msg("§7Turns every PvP module on or off at once.");
                msg("§7Off = modules disabled and their ticking skipped.");
                msg("§7Turning it back on restores what was enabled.");
            }
            case "utility" -> {
                msg("§6?bossaddon utility on|off§r");
                msg("§7Turns every Utility module on or off at once.");
                msg("§7Off = modules disabled and their ticking skipped.");
                msg("§7Turning it back on restores what was enabled.");
            }
            case "menu" -> {
                msg("§6?bossaddon menu simple|advanced§r");
                msg("§7Simple shows essential settings only.");
                msg("§7Advanced shows every setting.");
                msg("§7No argument flips between the two.");
            }
            case "chat" -> {
                msg("§6?bossaddon chat§r §7(talk across servers)");
                msg("  §fglobal§8 — §7Relay your chat to everyone");
                msg("  §fserver§8 — §7Relay to this server only");
                msg("  §foff§8 — §7Stop relaying, still receive");
                msg("  §fg §7<msg>§8 — §7Send one line to global");
                msg("  §fs §7<msg>§8 — §7Send one line to server");
                msg("  §fdm §7<user> <msg>§8 — §7Private message someone");
                msg("  §freconnect§8 — §7Reconnect to the relay");
                msg("  §fdisable§8 / §fenable§8 — §7Turn BossChat fully off/on");
            }
            case "party" -> {
                msg("§6?bossaddon party§r §7(private group chat)");
                msg("  §f<user>§8 — §7Invite a player to your party");
                msg("  §faccept§8 / §fdecline§8 — §7Answer an invite");
                msg("  §fleave§8 — §7Leave your current party");
                msg("  §flist§8 — §7Show who is in the party");
                msg("  §fmsg §7<msg>§8 — §7Send one line to the party");
                msg("  §fwarp §7[user]§8 — §7Ask them to join your server");
                msg("  §fwarp accept§8 / §fwarp decline§8 — §7Answer a warp");
            }
            default -> {
                msg("§7No help for §f" + topic + "§7. Try one of:");
                msg("§7  pvp, utility, menu, chat, party");
            }
        }
        return 1;
    }

    private static void msg(String s) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.sendSystemMessage(net.minecraft.network.chat.Component.literal(s));
        }
    }
}
