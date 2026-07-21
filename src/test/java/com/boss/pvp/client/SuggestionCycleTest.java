package com.boss.pvp.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the pure cycle/insertion math shared by the chat mixin (Tab insertion) and the suggestion popup (which
 * entry is highlighted). The suggestion <em>content</em> is tested in {@code CommandInterceptTest}; here we pin
 * down how Tab walks that list and what text an insertion produces, so the popup highlight and the actual
 * insertion can never disagree.
 */
class SuggestionCycleTest {

    @Test
    void baseIsEverythingUpToAndIncludingLastSpace() {
        assertEquals("", SuggestionCycle.baseOf(""));
        assertEquals("", SuggestionCycle.baseOf("pv"));          // completing the first token
        assertEquals("", SuggestionCycle.baseOf("party"));
        assertEquals("pvp ", SuggestionCycle.baseOf("pvp "));    // completing an arg after a space
        assertEquals("pvp ", SuggestionCycle.baseOf("pvp on"));
        assertEquals("chat ", SuggestionCycle.baseOf("chat gl"));
        assertEquals("", SuggestionCycle.baseOf(null));
    }

    @Test
    void nextIndexWrapsAround() {
        assertEquals(1, SuggestionCycle.nextIndex(0, 2));
        assertEquals(0, SuggestionCycle.nextIndex(1, 2));        // wrap back to the top
        assertEquals(0, SuggestionCycle.nextIndex(0, 1));        // single entry stays put
        assertEquals(0, SuggestionCycle.nextIndex(5, 0));        // empty list never indexes out of range
    }

    @Test
    void clampKeepsHighlightInRange() {
        assertEquals(0, SuggestionCycle.clampIndex(0, 3));
        assertEquals(2, SuggestionCycle.clampIndex(2, 3));
        assertEquals(2, SuggestionCycle.clampIndex(3, 3));       // stale index at the end -> last row, never overflows
        assertEquals(2, SuggestionCycle.clampIndex(4, 3));       // further past the end -> still the last row
        assertEquals(0, SuggestionCycle.clampIndex(-1, 3));      // below 0 -> first row
        assertEquals(0, SuggestionCycle.clampIndex(2, 0));       // empty list -> 0
    }

    @Test
    void appliedReplacesTheFinalTokenWithTheCompletion() {
        // First token: "?pv" + Tab -> "?pvp" (whole token replaced, no stray prefix text).
        assertEquals("?pvp", SuggestionCycle.applied("?", "pv", "pvp"));
        assertEquals("?party", SuggestionCycle.applied("?", "p", "party"));
        // Arg after a space: the fixed "pvp " base is kept, only the arg token is filled/cycled.
        assertEquals("?pvp on", SuggestionCycle.applied("?", "pvp ", "on"));
        assertEquals("?pvp off", SuggestionCycle.applied("?", "pvp on", "off"));   // cycling on -> off
        assertEquals("?menu simple", SuggestionCycle.applied("?", "menu ", "simple"));
    }
}
