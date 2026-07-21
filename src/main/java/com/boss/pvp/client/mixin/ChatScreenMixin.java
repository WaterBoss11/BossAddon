package com.boss.pvp.client.mixin;

import com.boss.pvp.relay.RelayConfig;
import com.boss.pvp.relay.RelayManager;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
 * <p>Live suggestions for {@code "?"} commands are handled separately by
 * {@link BossCommandSuggestionsMixin}, which drives the real vanilla command-suggestion dropdown (arrow-key/Tab
 * navigation included). This mixin only owns the scope-tab bar and the {@code "?"} interception below.
 *
 * <p>The mixin declares {@code extends Screen} purely so it can call the inherited protected
 * {@code addRenderableWidget} and read {@code width}/{@code height}/{@code font}; the constructor is never used
 * at runtime (the mixin is merged into {@link ChatScreen}).
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {

    @Shadow protected EditBox input;

    private ChatScreenMixin(Component title) {
        super(title);
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
