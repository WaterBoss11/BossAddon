package com.boss.pvp.client.mixin;

import com.boss.pvp.relay.RelayConfig;
import com.boss.pvp.relay.RelayManager;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Adds the BossChat scope-tab bar to the vanilla chat screen: a row of clickable tabs — GLOBAL / SERVER / PARTY
 * / DM — just above the chat input. Each tab is a natively-clickable {@link com.boss.pvp.client.gui.ScopeTabWidget}
 * (no custom mouse handling needed); clicking one switches the BossChat send-scope, exactly like the
 * {@code ?bossaddon chat} commands, and the active scope is highlighted. The bar is only added when the relay
 * gate ({@link RelayConfig#isConfigured()}) is satisfied, so an install with no relay URL sees nothing.
 *
 * <p>While a scope is active AND the relay is connected, a typed chat line is redirected to the relay instead of
 * the server (see the {@code handleChatInput} injection below; {@code require = 0} so a mapping mismatch can't
 * break the build — the guaranteed send path is the {@code ?bossaddon chat} command, this is the convenience path).
 *
 * <p>Also provides <b>Tab-completion</b> for {@code "?"} input: pressing Tab cycles through BossAddon's own
 * Brigadier completions (subcommands and their arguments). Vanilla's live suggestion dropdown
 * ({@code CommandSuggestions}) is hardcoded to {@code "/"} and the vanilla dispatcher, so it can't be reused for
 * {@code "?"} — this drives Tab-cycle from the addon's dispatcher instead (no dropdown).
 *
 * <p>The mixin declares {@code extends Screen} purely so it can call the inherited protected
 * {@code addRenderableWidget} and read {@code width}/{@code height}/{@code font}; the constructor is never used
 * at runtime (the mixin is merged into {@link ChatScreen}).
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {

    @Shadow protected EditBox input;

    // Tab-completion cycle state for "?" input: recomputed when the completed-tokens base changes, cycled on
    // repeated Tab. Vanilla's dropdown (CommandSuggestions) is hardcoded to "/" + the vanilla dispatcher, so
    // this drives Tab-cycle from BossAddon's own Brigadier tree instead.
    @Unique private java.util.List<String> bosspvp$completions;
    @Unique private String bosspvp$completeBase;
    @Unique private int bosspvp$completeIndex;

    private ChatScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$tabComplete(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() != 258) return;                  // GLFW_KEY_TAB
        String text = this.input == null ? null : this.input.getValue();
        if (text == null || !text.startsWith(com.boss.pvp.BossAddonInit.PREFIX)) return;   // only our "?" input
        String withoutPrefix = text.substring(com.boss.pvp.BossAddonInit.PREFIX.length());
        // Everything up to and including the last space is fixed; the final (partial) token gets completed.
        int lastSpace = withoutPrefix.lastIndexOf(' ');
        String base = lastSpace < 0 ? "" : withoutPrefix.substring(0, lastSpace + 1);
        if (!base.equals(bosspvp$completeBase) || bosspvp$completions == null) {
            bosspvp$completions = com.boss.pvp.BossAddonInit.suggest(withoutPrefix);
            bosspvp$completeBase = base;
            bosspvp$completeIndex = 0;
        } else if (!bosspvp$completions.isEmpty()) {
            bosspvp$completeIndex = (bosspvp$completeIndex + 1) % bosspvp$completions.size();
        }
        if (bosspvp$completions.isEmpty()) { cir.setReturnValue(true); return; }   // no match: swallow Tab
        String completion = bosspvp$completions.get(bosspvp$completeIndex);
        this.input.setValue(com.boss.pvp.BossAddonInit.PREFIX + base + completion);
        this.input.moveCursorToEnd(false);
        cir.setReturnValue(true);   // consumed Tab (don't let vanilla/CommandSuggestions also handle it)
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void bosspvp$addScopeTabs(CallbackInfo ci) {
        if (!RelayConfig.isConfigured()) return;
        net.minecraft.client.gui.Font font = this.font;
        int h = 13;
        int y = this.height - 30;     // a row of scope tabs just above the vanilla chat input line
        int x = 4;                    // aligned to the LEFT of the chat box
        // GLOBAL / SERVER / PARTY / DM, accent colours matching the chat scope colours (aqua/green/gold/purple).
        x = bosspvp$tab(x, y, h, font, "GLOBAL", RelayManager.Mode.GLOBAL, false, 0xFF4FC3F7);
        x = bosspvp$tab(x, y, h, font, "SERVER", RelayManager.Mode.SERVER, false, 0xFF66DD66);
        x = bosspvp$tab(x, y, h, font, "PARTY",  RelayManager.Mode.PARTY,  false, 0xFFFFB74D);
        x = bosspvp$tab(x, y, h, font, "DM",     null,                     true,  0xFFBA68C8);
    }

    /** Add one scope tab and return the x for the next tab (1px gap between tabs). */
    private int bosspvp$tab(int x, int y, int h, net.minecraft.client.gui.Font font, String label,
                            RelayManager.Mode scope, boolean dm, int accent) {
        int w = font.width(label) + 12;
        this.addRenderableWidget(new com.boss.pvp.client.gui.ScopeTabWidget(x, y, w, h, label, scope, dm, accent));
        return x + w + 1;
    }

    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$interceptInput(String message, boolean addToHistory, CallbackInfo ci) {
        if (message == null || message.isEmpty()) return;

        // 1) ANY line starting with "?" is intercepted here, BEFORE it can be sent as chat. "?" is not a
        // Minecraft command char, so it never reaches the server. A recognized ?bossaddon command runs;
        // anything else ("?", "?hi", "?whatever") gets a local "unknown command" notice from dispatch() — so
        // nothing beginning with "?" is ever sent to the server or other players.
        if (com.boss.pvp.BossAddonInit.shouldIntercept(message)) {
            if (addToHistory) bosspvp$addRecent(message);
            com.boss.pvp.BossAddonInit.dispatch(message.substring(com.boss.pvp.BossAddonInit.PREFIX.length()));
            ci.cancel();   // consumed locally — never sent to the server as chat
            return;
        }

        // 2) Relay redirect: a typed line goes to the relay when a scope is active. "/" commands never touched.
        if (message.startsWith("/")) return;
        RelayManager relay = RelayManager.get();
        if (!relay.shouldRedirectChat()) return;
        if (addToHistory) bosspvp$addRecent(message);
        relay.sendTyped(message);
        ci.cancel();   // consumed by the relay — do not also send to the Minecraft server
    }

    /**
     * Preserve vanilla up-arrow recall: cancelling handleChatInput skips its own addRecentChat, so an
     * intercepted line would never enter the history. Add it here exactly as vanilla would.
     */
    private static void bosspvp$addRecent(String message) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        // 26.2: ChatComponent is reached via Gui -> Hud -> getChat() (vanilla's own handleChatInput path).
        if (mc.gui != null && mc.gui.hud != null) mc.gui.hud.getChat().addRecentChat(message);
    }
}
