package com.boss.pvp.client.mixin;

import com.boss.pvp.relay.RelayConfig;
import com.boss.pvp.relay.RelayManager;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds the chat-relay toggle to the vanilla chat screen (Option A from the design doc: a real, natively
 * clickable widget — no custom mouse handling needed). The button is only added when the relay gate
 * ({@link RelayConfig#isConfigured()}) is satisfied — i.e. a relay URL is configured — so an install with no
 * relay URL sees nothing.
 *
 * <p>Clicking cycles OFF → GLOBAL → SERVER. While a scope is active AND the relay is connected, a typed chat
 * line is redirected to the relay instead of the server (commands starting with {@code /} are never touched).
 * The redirect injects into {@code handleChatInput} with {@code require = 0} so a mapping mismatch can't break
 * the build — the guaranteed send path is the {@code ?bossaddon chat} command; this is the convenience path.
 *
 * <p>The mixin declares {@code extends Screen} purely so it can call the inherited protected
 * {@code addRenderableWidget} and read {@code width}/{@code height}; the constructor is never used at runtime
 * (the mixin is merged into {@link ChatScreen}).
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen {

    private ChatScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void bosspvp$addRelayToggle(CallbackInfo ci) {
        if (!RelayConfig.isConfigured()) return;
        RelayManager relay = RelayManager.get();
        int h = 16;
        int w = this.font.width(RelayManager.buttonLabelWidest()) + 12;   // fits the widest state label
        int x = 4;                    // LEFT side of the chat box
        int y = this.height - 32;     // just above the vanilla chat input line
        Button button = Button.builder(Component.literal(relay.buttonLabel()), b -> {
            relay.cycleMode();
            b.setMessage(Component.literal(relay.buttonLabel()));
        }).bounds(x, y, w, h).build();
        this.addRenderableWidget(button);
    }

    @Inject(method = "handleChatInput", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$interceptInput(String message, boolean addToHistory, CallbackInfo ci) {
        if (message == null || message.isEmpty()) return;

        // 1) "?bossaddon ..." -> a BossAddon command. "?" is not a Minecraft command char, so we intercept it
        // here BEFORE the line is sent as chat. Only a leading "?bossaddon" token qualifies (see isBossCommand);
        // any other "?..." (a lone "?", "?hi", a server chat-plugin command) falls through to normal chat.
        if (com.boss.pvp.BossAddonInit.isBossCommand(message)) {
            if (addToHistory) bosspvp$addRecent(message);
            com.boss.pvp.BossAddonInit.dispatch(message.substring(com.boss.pvp.BossAddonInit.PREFIX.length()));
            ci.cancel();   // consumed as a command — never sent to the server as chat
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
