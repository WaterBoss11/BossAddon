package com.boss.pvp.client.mixin;

import com.boss.pvp.BossAddonInit;

import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;

import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.multiplayer.ClientSuggestionProvider;
import net.minecraft.util.FormattedCharSequence;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Gives BossAddon's {@code "?"} commands the <b>same native suggestion dropdown</b> that vanilla {@code "/"}
 * commands use — the real {@link CommandSuggestions} widget, with its own arrow-key/Tab navigation, mouse
 * clicking and rendering — rather than a separately drawn popup.
 *
 * <p>Vanilla's {@code updateCommandInfo} rebuilds that widget on every chat-input change, but only ever for
 * {@code "/"} (the server command tree). This mixes into it at the head: when the line starts with our
 * {@code "?"} prefix, it parses the text with BossAddon's own flat suggestion tree
 * ({@link BossAddonInit#suggestionsFor}) and drops the resulting parse and completions into the widget's own
 * fields ({@code currentParse}, {@code pendingSuggestions}), then calls the widget's real
 * {@code showSuggestions} — so vanilla renders and drives the dropdown natively — and cancels the rest of the
 * vanilla method. For {@code "/"} lines and ordinary chat it does nothing and lets vanilla run as usual.
 *
 * <p>The {@code currentParse} field is typed for the server's {@code ClientSuggestionProvider}; our parse uses a
 * throwaway source, so it is assigned through an unchecked cast. That is safe because generics are erased at
 * runtime and nothing downstream calls back into the parse's source — the widget only reads the completed
 * {@link Suggestions} to build its list. (Technique studied from AUTISM Client's own prefix-command hook;
 * implemented here in BossAddon's own code against BossAddon's dispatcher.)
 */
@Mixin(CommandSuggestions.class)
public abstract class BossCommandSuggestionsMixin {

    @Shadow @Final private EditBox input;
    @Shadow @Nullable private ParseResults<ClientSuggestionProvider> currentParse;
    @Shadow @Nullable private CompletableFuture<Suggestions> pendingSuggestions;
    @Shadow @Nullable private CommandSuggestions.SuggestionsList suggestions;
    @Shadow @Final private List<FormattedCharSequence> commandUsage;
    @Shadow private boolean currentParseIsCommand;
    @Shadow private boolean currentParseIsMessage;
    @Shadow private boolean keepSuggestions;

    @Shadow public abstract void showSuggestions(boolean narrateFirstSuggestion);

    @Shadow private void updateUsageInfo(ParseResults<ClientSuggestionProvider> parse, Suggestions result) { }

    // Whether we currently own the widget's suggestion state, so we can hand it cleanly back to vanilla when the
    // line stops being a "?" command.
    @Unique private boolean bosspvp$owning;

    @Inject(method = "updateCommandInfo", at = @At("HEAD"), cancellable = true)
    private void bosspvp$suggestBossCommands(CallbackInfo ci) {
        String value = input.getValue();
        if (value == null || !BossAddonInit.shouldIntercept(value)) {
            if (bosspvp$owning) bosspvp$clearSuggestions();   // left "?" input: return the widget to vanilla
            return;                                           // "/" commands and normal chat: vanilla handles it
        }

        ParseResults<Object> parse = BossAddonInit.parseSuggest(value);
        @SuppressWarnings("unchecked")
        ParseResults<ClientSuggestionProvider> widgetParse = (ParseResults<ClientSuggestionProvider>) (Object) parse;

        currentParse = widgetParse;
        currentParseIsCommand = true;
        currentParseIsMessage = false;
        commandUsage.clear();

        // Keep the current dropdown across the input change a Tab/click completion just made. useSuggestion() sets
        // keepSuggestions=true and rewrites the input to an applied suggestion; vanilla preserves the list in that
        // case so Tab-cycling keeps advancing. Without this we rebuilt (and reset the selection to the top) on
        // every Tab, which is exactly what made Tab "insert whatever's highlighted" instead of moving to the next.
        if (suggestions != null && keepSuggestions && bosspvp$canKeepList(value)) {
            ci.cancel();
            return;
        }

        suggestions = null;
        keepSuggestions = false;

        String snapshot = value;
        // Complete the WHOLE last token (end of input), NOT the raw caret position: a caret sitting mid-token
        // would splice the completion into the middle and duplicate the trailing characters (garbled input like
        // "?pvp offo"). End-of-input completion always replaces the final token cleanly.
        CompletableFuture<Suggestions> future = BossAddonInit.suggestionsFor(value);
        pendingSuggestions = future;
        future.thenAccept(result -> {
            if (pendingSuggestions != future) return;              // superseded by a newer input
            if (!snapshot.equals(input.getValue())) return;        // input changed while we were completing
            bosspvp$owning = true;
            // We drive ONLY the dropdown, never the inline "ghost" completion vanilla otherwise draws next to the
            // caret. A stale ghost overlapping the typed text is exactly what makes the box look garbled (the
            // typed token plus a greyed completion rendered on top of each other), so always clear it.
            input.setSuggestion(null);
            if (result.isEmpty()) {
                // Nothing genuinely completes the current input (a typo or an invalid subcommand path): show
                // nothing at all — never a stale or partial dropdown.
                suggestions = null;
                keepSuggestions = false;
                return;
            }
            try {
                updateUsageInfo(widgetParse, result);
            } catch (Throwable ignored) {
                // usage text is cosmetic; never let it break the dropdown
            }
            if (pendingSuggestions == future && snapshot.equals(input.getValue())) {
                showSuggestions(false);   // false = don't narrate the first entry on every keystroke
                // ...but force Tab-cycling ON so each Tab advances the selection one step before applying, like
                // vanilla "/". (Vanilla derives tabCycles from showSuggestions' narrate flag; we want cycling
                // without the per-keystroke narration, so we set it directly on the freshly built list.)
                if (suggestions != null) {
                    ((CommandSuggestionsListAccessor) (Object) suggestions).bosspvp$setTabCycles(true);
                }
            }
        });
        ci.cancel();
    }

    /**
     * True when {@code currentInput} is simply one of the current dropdown's suggestions applied to the text the
     * list was built from — i.e. the input changed because a Tab/click completion was inserted, not because the
     * user typed. In that case the list is kept (and Tab-cycling survives) instead of rebuilt. Mirrors vanilla's
     * own keep-suggestions guard.
     */
    @Unique
    private boolean bosspvp$canKeepList(String currentInput) {
        if (suggestions == null || currentInput == null) return false;
        try {
            CommandSuggestionsListAccessor acc = (CommandSuggestionsListAccessor) (Object) suggestions;
            String original = acc.bosspvp$originalContents();
            List<Suggestion> list = acc.bosspvp$suggestionList();
            if (original == null || list == null || list.isEmpty()) return false;
            for (Suggestion s : list) {
                if (s == null) continue;
                try {
                    if (currentInput.equals(s.apply(original))) return true;
                } catch (Throwable ignored) {
                    return false;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    @Unique
    private void bosspvp$clearSuggestions() {
        input.setSuggestion(null);
        suggestions = null;
        pendingSuggestions = null;
        keepSuggestions = false;
        bosspvp$owning = false;
    }
}
