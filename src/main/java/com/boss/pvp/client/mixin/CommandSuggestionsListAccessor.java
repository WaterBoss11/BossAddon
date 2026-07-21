package com.boss.pvp.client.mixin;

import com.mojang.brigadier.suggestion.Suggestion;

import net.minecraft.client.gui.components.CommandSuggestions;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Accessor into vanilla's private {@code CommandSuggestions.SuggestionsList} so {@link BossCommandSuggestionsMixin}
 * can drive Tab-cycling for the "?" dropdown exactly like vanilla does for "/":
 * <ul>
 *   <li>{@code originalContents} + {@code suggestionList} let us tell "the input just became one of the current
 *       suggestions applied" (a Tab/click completion) from a fresh edit, so we keep the list across that change
 *       and cycling isn't reset; and</li>
 *   <li>{@code tabCycles} is forced on after the list is built, so each Tab advances the selection one step and
 *       then applies it (vanilla ties that flag to {@code showSuggestions}'s narrate flag, which we keep off to
 *       avoid narrating on every keystroke).</li>
 * </ul>
 */
@Mixin(CommandSuggestions.SuggestionsList.class)
public interface CommandSuggestionsListAccessor {

    @Accessor("originalContents")
    String bosspvp$originalContents();

    @Accessor("suggestionList")
    List<Suggestion> bosspvp$suggestionList();

    @Accessor("tabCycles")
    void bosspvp$setTabCycles(boolean tabCycles);
}
