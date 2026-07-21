package com.boss.pvp.client.mixin;

import com.boss.pvp.client.gui.BossSuggestionSource;
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
 * Brigadier completions (subcommands and their arguments), and a small custom-drawn
 * {@link com.boss.pvp.client.gui.SuggestionPopupWidget} above the input lists those completions live as you type,
 * highlighting the one Tab would select next. Vanilla's live suggestion dropdown ({@code CommandSuggestions}) is
 * hardcoded to {@code "/"} and the vanilla dispatcher, so it can't be reused for {@code "?"} — both the Tab-cycle
 * and the popup are driven from the addon's own Brigadier tree instead. This mixin is the single source of truth
 * for the cycle state (see {@link BossSuggestionSource}); the popup only renders it.
 *
 * <p>The mixin declares {@code extends Screen} purely so it can call the inherited protected
 * {@code addRenderableWidget} and read {@code width}/{@code height}/{@code font}; the constructor is never used
 * at runtime (the mixin is merged into {@link ChatScreen}).
 */
@Mixin(ChatScreen.class)
public abstract class ChatScreenMixin extends Screen implements BossSuggestionSource {

    @Shadow protected EditBox input;

    // Tab-completion cycle state for "?" input. Recomputed live as the typed text changes (so the popup narrows
    // as you type) and kept stable across our own Tab insertions (so repeated Tab cycles the same list). Vanilla's
    // dropdown (CommandSuggestions) is hardcoded to "/" + the vanilla dispatcher, so both the cycle and the popup
    // run off BossAddon's own Brigadier tree instead.
    @Unique private java.util.List<String> bosspvp$completions;
    @Unique private int bosspvp$completeIndex;
    @Unique private String bosspvp$lastText;      // input value the current list was computed for (typed changes)
    @Unique private String bosspvp$lastApplied;   // input value we last set via Tab (so it isn't seen as a retype)

    private ChatScreenMixin(Component title) {
        super(title);
    }

    /**
     * Refresh {@link #bosspvp$completions}/{@link #bosspvp$completeIndex} for the current input. Recomputes (and
     * resets the highlight to the top) only when the user changed the text by typing — a value we set ourselves
     * via Tab is remembered in {@link #bosspvp$lastApplied} and left alone, so repeated Tab keeps cycling one
     * stable list instead of collapsing to the just-inserted token. Called both per-frame by the popup and at the
     * start of a Tab press, so the two always agree.
     */
    @Unique
    private void bosspvp$refreshCompletions() {
        String text = this.input == null ? null : this.input.getValue();
        if (text == null || !text.startsWith(com.boss.pvp.BossAddonInit.PREFIX)) {
            bosspvp$completions = null;
            bosspvp$completeIndex = 0;
            bosspvp$lastText = null;
            bosspvp$lastApplied = null;
            return;
        }
        if (text.equals(bosspvp$lastApplied)) return;                          // our own insertion: keep list+index
        if (text.equals(bosspvp$lastText) && bosspvp$completions != null) return;   // unchanged typed text: no work
        String withoutPrefix = text.substring(com.boss.pvp.BossAddonInit.PREFIX.length());
        bosspvp$completions = com.boss.pvp.BossAddonInit.suggest(withoutPrefix);
        bosspvp$completeIndex = 0;                                             // typed: highlight the top match
        bosspvp$lastText = text;
        bosspvp$lastApplied = null;
    }

    @Override
    public BossSuggestionSource.Model bossSuggestionModel() {
        bosspvp$refreshCompletions();
        java.util.List<String> items = bosspvp$completions == null ? java.util.List.of() : bosspvp$completions;
        return new BossSuggestionSource.Model(items, bosspvp$completeIndex);
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void bosspvp$tabComplete(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (event.key() != 258) return;                  // GLFW_KEY_TAB
        String text = this.input == null ? null : this.input.getValue();
        if (text == null || !text.startsWith(com.boss.pvp.BossAddonInit.PREFIX)) return;   // only our "?" input
        boolean cycling = text.equals(bosspvp$lastApplied);   // repeated Tab on a line we ourselves inserted
        bosspvp$refreshCompletions();
        if (bosspvp$completions == null || bosspvp$completions.isEmpty()) { cir.setReturnValue(true); return; }  // no match: swallow Tab
        if (cycling) {
            bosspvp$completeIndex = com.boss.pvp.client.SuggestionCycle.nextIndex(bosspvp$completeIndex, bosspvp$completions.size());
        }
        String withoutPrefix = text.substring(com.boss.pvp.BossAddonInit.PREFIX.length());
        String completion = bosspvp$completions.get(bosspvp$completeIndex);
        String applied = com.boss.pvp.client.SuggestionCycle.applied(com.boss.pvp.BossAddonInit.PREFIX, withoutPrefix, completion);
        this.input.setValue(applied);
        this.input.moveCursorToEnd(false);
        bosspvp$lastApplied = applied;      // so the next refresh keeps this list and the cycle stays stable
        bosspvp$lastText = applied;
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

        // The "?" suggestion popup, bottom-anchored just above the tab row and growing upward. It draws nothing
        // unless the current input is a "?" line with matches, so a non-"?" chat line shows only the tabs.
        int popupBottom = y - 2;
        int popupHeight = 8 * 12;     // room for MAX_ROWS rows; the box only fills what the list needs
        this.addRenderableWidget(new com.boss.pvp.client.gui.SuggestionPopupWidget(
                4, popupBottom - popupHeight, 180, popupHeight, this));
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
