package com.boss.pvp.relay;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side brain of the boss-pvp chat relay (Phase 1: client↔client broadcast; no Discord, no moderation).
 * Owns the connection lifecycle, the Mojang auth handshake, the outbound scope state, reporting the current
 * Minecraft server (for server-only routing), and displaying inbound messages in chat.
 *
 * <p><b>Closed pilot:</b> completely inert unless {@link RelayConfig#isConfigured()} (a URL + invite the user
 * was given). A default public install never connects and shows no relay UI.
 *
 * <p><b>Scopes:</b> {@link Mode#OFF} lets chat behave normally; {@link Mode#GLOBAL} and {@link Mode#SERVER}
 * redirect what you type into chat to the relay (global = everyone connected; server = only addon users on the
 * SAME Minecraft server). Direct messages are sent via {@code /bossrelay dm <user> <text>} and are delivered
 * only to that one recipient — never echoed, logged, or relayed anywhere else.
 *
 * <p>Threading: WebSocket callbacks land on HTTP-client threads; all Minecraft/chat access is bounced to the
 * game thread via {@link Minecraft#execute}. Reconnect/auth run on a single daemon scheduler.
 */
public final class RelayManager implements RelayClient.Handler {

    public enum Mode { OFF, GLOBAL, SERVER }

    private static final RelayManager INSTANCE = new RelayManager();
    public static RelayManager get() { return INSTANCE; }

    private RelayManager() {}

    private static final long BACKOFF_START_MS = 2_000L;
    private static final long BACKOFF_MAX_MS = 60_000L;

    private final ScheduledExecutorService sched = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "boss-pvp-relay");
        t.setDaemon(true);
        return t;
    });

    private volatile RelayClient client;
    private volatile Mode mode = Mode.OFF;
    private volatile boolean authed = false;
    private volatile boolean fatal = false;          // rejected (invite/allowlist/auth) — stop auto-retry until manual reconnect
    private volatile String status = "off";
    private volatile String lastServer = null;
    private final AtomicInteger backoffAttempt = new AtomicInteger(0);

    // ---- lifecycle -----------------------------------------------------------------------------------

    /** Call once on addon init. No-op unless the pilot gate is configured. */
    public void init() {
        if (!RelayConfig.isConfigured()) {
            status = "disabled (no pilot invite configured)";
            return;
        }
        connect();
    }

    /** (Re)connect from scratch. Safe to call repeatedly. */
    public synchronized void connect() {
        if (!RelayConfig.isConfigured()) return;
        fatal = false;
        authed = false;
        status = "connecting";
        String url = RelayConfig.url();
        try {
            client = new RelayClient(URI.create(url), this);
            client.connect();
        } catch (Throwable t) {
            status = "connect error";
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (fatal || !RelayConfig.isConfigured()) return;
        int attempt = backoffAttempt.getAndIncrement();
        long delay = Math.min(BACKOFF_START_MS << Math.min(attempt, 5), BACKOFF_MAX_MS);
        status = "reconnecting in " + (delay / 1000) + "s";
        sched.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    // ---- transport callbacks (off-thread) ------------------------------------------------------------

    @Override public void onOpen() {
        status = "authenticating";
    }

    @Override public void onClosed(String reason) {
        authed = false;
        status = "disconnected";
        if (!fatal) scheduleReconnect();
    }

    @Override public void onMessage(String json) {
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            String t = str(o, "t");
            if (t == null) return;
            switch (t) {
                case "hello"  -> onHello(str(o, "serverId"));
                case "authok" -> onAuthOk();
                case "msg"    -> onInboundMessage(o);
                case "system" -> display("§7[Relay] " + str(o, "text"));
                case "error"  -> onError(str(o, "reason"));
                default -> { /* unknown type — ignore */ }
            }
        } catch (Throwable ignored) {
            // malformed frame — ignore, never trust the wire
        }
    }

    // ---- handshake -----------------------------------------------------------------------------------

    private void onHello(String serverId) {
        if (serverId == null) return;
        status = "authenticating";
        sched.execute(() -> {
            try {
                User user = Minecraft.getInstance().getUser();
                if (user == null) { closeQuietly(); return; }
                String name = user.getName();
                String uuid = user.getProfileId() == null ? "" : user.getProfileId().toString();
                String token = user.getAccessToken();

                boolean ok = MojangAuth.joinServer(token, uuid, serverId);
                if (!ok) {
                    display("§c[Relay] Mojang session auth failed — not connecting.");
                    closeQuietly();
                    return;
                }
                JsonObject auth = new JsonObject();
                auth.addProperty("t", "auth");
                auth.addProperty("username", name);
                auth.addProperty("uuid", uuid);
                auth.addProperty("invite", RelayConfig.invite());
                auth.addProperty("server", currentServerId());
                RelayClient c = client;
                if (c != null) c.send(auth.toString());
            } catch (Throwable t) {
                closeQuietly();
            }
        });
    }

    private void onAuthOk() {
        authed = true;
        fatal = false;
        backoffAttempt.set(0);
        status = "connected";
        lastServer = null;
        reportServer(true);
        display("§a[Relay] Connected. Type in chat while relay is on, or use §f/bossrelay§a.");
    }

    private void onError(String reason) {
        display("§c[Relay] Rejected: " + (reason == null ? "unknown" : reason));
        // Auth/gate rejections won't fix themselves on retry — stop hammering until the user reconnects.
        if (reason != null) {
            String r = reason.toLowerCase();
            if (r.contains("allowlist") || r.contains("invite") || r.contains("auth")) fatal = true;
        }
    }

    // ---- outbound ------------------------------------------------------------------------------------

    /** True only when a typed chat line should be redirected to the relay (mode on AND actually connected). */
    public boolean shouldRedirectChat() {
        return mode != Mode.OFF && authed;
    }

    /** Redirect a typed chat line (from the ChatScreen mixin) to the relay using the current scope. */
    public void sendTyped(String text) {
        send(mode == Mode.SERVER ? "server" : "global", null, text);
    }

    public void sendGlobal(String text) { send("global", null, text); }
    public void sendServer(String text) { send("server", null, text); }
    public void sendDm(String toUser, String text) { send("dm", toUser, text); }

    private void send(String scope, String to, String text) {
        if (!authed) { display("§c[Relay] Not connected."); return; }
        String clean = RelaySanitizer.sanitize(text);
        if (clean.isEmpty()) return;
        RelayClient c = client;
        if (c == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "msg");
        o.addProperty("scope", scope);
        if (to != null) o.addProperty("to", to);
        o.addProperty("text", clean);
        c.send(o.toString());
        echoLocal(scope, to, clean);
    }

    // ---- server reporting (for server-only routing) --------------------------------------------------

    /** Report the current Minecraft server to the relay if it changed. Cheap; safe to call every tick. */
    public void reportServer(boolean force) {
        if (!authed) return;
        String id = currentServerId();
        if (!force && id.equals(lastServer)) return;
        lastServer = id;
        RelayClient c = client;
        if (c == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("t", "server");
        o.addProperty("server", id);
        c.send(o.toString());
    }

    /** The identifier the relay groups "server-only" messages by — the multiplayer address, or singleplayer. */
    private String currentServerId() {
        try {
            ServerData sd = Minecraft.getInstance().getCurrentServer();
            if (sd == null || sd.ip == null || sd.ip.isBlank()) return "singleplayer";
            String ip = sd.ip.strip();
            return ip.length() > 100 ? ip.substring(0, 100) : ip;
        } catch (Throwable t) {
            return "singleplayer";
        }
    }

    /** Per-tick hook (from the addon's onTick): keeps the relay's notion of your current server current. */
    public void tick(Minecraft mc) {
        if (authed) reportServer(false);
    }

    // ---- UI state ------------------------------------------------------------------------------------

    public Mode mode() { return mode; }
    public boolean isAuthed() { return authed; }
    public String status() { return status; }

    /** Cycle OFF → GLOBAL → SERVER → OFF (the chat-toggle button). */
    public void cycleMode() {
        mode = switch (mode) {
            case OFF -> Mode.GLOBAL;
            case GLOBAL -> Mode.SERVER;
            case SERVER -> Mode.OFF;
        };
    }

    public void setMode(Mode m) { this.mode = m; }

    /** Short label for the toggle button / status line. */
    public String buttonLabel() {
        String tag = switch (mode) {
            case OFF -> "off";
            case GLOBAL -> "GLOBAL";
            case SERVER -> "server";
        };
        if (mode != Mode.OFF && !authed) tag += " …";
        return "Relay: " + tag;
    }

    // ---- display -------------------------------------------------------------------------------------

    private void onInboundMessage(JsonObject o) {
        String scope = str(o, "scope");
        String from = str(o, "from");
        String text = str(o, "text");
        if (from == null || text == null) return;
        // Sanitize again on display — never render raw wire text into chat.
        String safe = RelaySanitizer.sanitize(text);
        if (safe.isEmpty()) return;
        String line = switch (scope == null ? "" : scope) {
            case "server" -> "§a[Relay·server] §f" + from + "§7: §f" + safe;
            case "dm" -> "§d[Relay·DM] §f" + from + " §7→ you: §f" + safe;
            default -> "§b[Relay] §f" + from + "§7: §f" + safe;
        };
        display(line);
    }

    private void echoLocal(String scope, String to, String text) {
        String line = switch (scope) {
            case "server" -> "§a[Relay·server] §7you: §f" + text;
            case "dm" -> "§d[Relay·DM] §7you → §f" + to + "§7: §f" + text;
            default -> "§b[Relay] §7you: §f" + text;
        };
        display(line);
    }

    private void display(String s) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) mc.player.sendSystemMessage(Component.literal(s));
            else System.out.println("[boss-pvp/relay] " + s);
        });
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private void closeQuietly() {
        RelayClient c = client;
        if (c != null) c.close();
    }

    private static String str(JsonObject o, String key) {
        try {
            return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
        } catch (Throwable t) {
            return null;
        }
    }
}
