package com.boss.pvp.client.gui;

import java.util.List;

/**
 * Live feed of the current "?" command completions from the chat screen to the {@link SuggestionPopupWidget}.
 * The chat mixin ({@code ChatScreenMixin}) is the single source of truth for the Tab-cycle state and implements
 * this so the popup can draw exactly the list the cycle is walking, with the same highlighted entry — no
 * independent recomputation that could drift from what Tab actually inserts.
 */
public interface BossSuggestionSource {

    /** The current suggestion list and which entry is highlighted (the one Tab would select). Never null. */
    Model bossSuggestionModel();

    /**
     * @param items     the current completions (subcommands or their args); empty means "draw nothing"
     * @param highlight index into {@code items} of the highlighted entry (what Tab currently selects)
     */
    record Model(List<String> items, int highlight) {}
}
