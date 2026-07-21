package com.boss.pvp;

import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the data behind the native "?" command dropdown ({@link BossAddonInit#suggestionsFor}), which feeds
 * vanilla's own {@code CommandSuggestions} widget via {@code BossCommandSuggestionsMixin}. The widget itself
 * can't be unit-tested (it needs a client), but the crucial property CAN: the Brigadier suggestions must carry
 * character ranges that line up with the real chat-input string, including the leading {@code "?"}. We assert
 * that by checking {@link Suggestion#apply} splices each completion back into the exact full line the user would
 * see — if the ranges were off by the prefix (or the "bossaddon" root), apply() would produce garbage.
 */
class NativeSuggestTest {

    private static Suggestions at(String fullValue, int cursor) {
        return BossAddonInit.suggestionsFor(fullValue, cursor).join();
    }

    /** Complete at end-of-input — exactly what the live dropdown does (see BossCommandSuggestionsMixin). */
    private static Suggestions at(String fullValue) {
        return BossAddonInit.suggestionsFor(fullValue).join();
    }

    private static List<String> texts(Suggestions s) {
        return s.getList().stream().map(Suggestion::getText).collect(Collectors.toList());
    }

    private static List<String> applied(Suggestions s, String fullValue) {
        return s.getList().stream().map(sug -> sug.apply(fullValue)).collect(Collectors.toList());
    }

    private static List<String> appliedEnd(String fullValue) {
        return applied(at(fullValue), fullValue);
    }

    @Test
    void bareQuestionMarkSuggestsEverySubcommand() {
        Suggestions s = at("?", 1);
        assertTrue(texts(s).containsAll(List.of("help", "pvp", "utility", "menu", "chat", "party")),
            "top-level subcommands: " + texts(s));
        // Every suggestion replaces the empty token right after "?", so applying yields "?<sub>".
        for (Suggestion sug : s.getList()) {
            assertEquals(1, sug.getRange().getStart(), "range starts just after the ? prefix");
            assertEquals("?" + sug.getText(), sug.apply("?"), "apply splices onto the real input");
        }
    }

    @Test
    void partialFirstTokenNarrowsAndRangesAlign() {
        Suggestions s = at("?p", 2);
        assertEquals(List.of("party", "pvp"), texts(s), "p -> party/pvp: " + texts(s));
        // The key alignment property: ranges include the "?" offset, so apply() rebuilds the exact line.
        assertEquals(List.of("?party", "?pvp"), applied(s, "?p"));
        for (Suggestion sug : s.getList()) {
            assertEquals(1, sug.getRange().getStart());
            assertEquals(2, sug.getRange().getEnd());
        }
    }

    @Test
    void argumentsSuggestAfterASpaceWithAlignedRanges() {
        Suggestions pvp = at("?pvp ", 5);
        assertTrue(texts(pvp).contains("on") && texts(pvp).contains("off"), "pvp args: " + texts(pvp));
        assertTrue(applied(pvp, "?pvp ").containsAll(List.of("?pvp on", "?pvp off")), applied(pvp, "?pvp ").toString());

        Suggestions partial = at("?pvp o", 6);   // completing a partial arg "o"
        assertTrue(applied(partial, "?pvp o").containsAll(List.of("?pvp on", "?pvp off")),
            "partial arg completes in place: " + applied(partial, "?pvp o"));

        Suggestions menu = at("?menu ", 6);
        assertTrue(texts(menu).contains("simple") && texts(menu).contains("advanced"), "menu args: " + texts(menu));
    }

    @Test
    void unmatchedInputYieldsNoSuggestions() {
        assertTrue(at("?zzz", 4).getList().isEmpty(), "unknown subcommand -> empty dropdown");
    }

    // ---- long form: "?bossaddon <sub>" gets the same dropdown, with ranges offset past "?bossaddon " ---------

    @Test
    void longFormAfterBossaddonSpaceSuggestsEverySubcommand() {
        Suggestions s = at("?bossaddon ", 11);   // cursor just past "?bossaddon "
        assertTrue(texts(s).containsAll(List.of("help", "pvp", "utility", "menu", "chat", "party")),
            "subcommands after the long prefix: " + texts(s));
        for (Suggestion sug : s.getList()) {
            assertEquals(11, sug.getRange().getStart(), "range starts just past \"?bossaddon \"");
            assertEquals("?bossaddon " + sug.getText(), sug.apply("?bossaddon "),
                "apply splices onto the full long-form line");
        }
    }

    @Test
    void longFormPartialSubcommandNarrowsAndRangesAlign() {
        Suggestions s = at("?bossaddon p", 12);
        assertEquals(List.of("party", "pvp"), texts(s), "p -> party/pvp: " + texts(s));
        // The key long-form property: ranges are offset past "?bossaddon ", so apply() rebuilds the exact line.
        assertEquals(List.of("?bossaddon party", "?bossaddon pvp"), applied(s, "?bossaddon p"));
        for (Suggestion sug : s.getList()) {
            assertEquals(11, sug.getRange().getStart());
            assertEquals(12, sug.getRange().getEnd());
        }
    }

    @Test
    void longFormArgumentsSuggestWithAlignedRanges() {
        Suggestions pvp = at("?bossaddon pvp ", 15);
        assertTrue(applied(pvp, "?bossaddon pvp ").containsAll(List.of("?bossaddon pvp on", "?bossaddon pvp off")),
            applied(pvp, "?bossaddon pvp ").toString());

        Suggestions partial = at("?bossaddon pvp o", 16);   // completing a partial arg in the long form
        assertTrue(applied(partial, "?bossaddon pvp o").containsAll(List.of("?bossaddon pvp on", "?bossaddon pvp off")),
            "partial long-form arg completes in place: " + applied(partial, "?bossaddon pvp o"));

        Suggestions menu = at("?bossaddon menu ", 16);
        assertTrue(texts(menu).contains("simple") && texts(menu).contains("advanced"), "menu args: " + texts(menu));
    }

    @Test
    void shortAndLongFormsOfferTheSameSubcommandSet() {
        // Same data source: the completion texts are identical, only the ranges differ by the prefix length.
        assertEquals(texts(at("?p", 2)), texts(at("?bossaddon p", 12)), "short vs long form completions match");
        assertEquals(texts(at("?pvp ", 5)), texts(at("?bossaddon pvp ", 15)), "short vs long form args match");
    }

    @Test
    void shortFormListStaysFreeOfTheBossaddonLiteral() {
        // The long-form support must not leak a "bossaddon" entry into the short-form "?" dropdown (unchanged).
        assertFalse(texts(at("?", 1)).contains("bossaddon"), "\"?\" must not suggest the bossaddon literal itself");
    }

    // ---- explicit "party invite <user>" keyword in the dropdown ------------------------------------------

    // ---- issue #2: no dropdown when nothing genuinely matches -------------------------------------------

    @Test
    void noValidCompletionYieldsAnEmptyDropdown() {
        // Typos and invalid subcommand paths must produce zero suggestions, so the mixin shows nothing (rather
        // than a stale/partial dropdown). The mixin gates showSuggestions on this being non-empty.
        for (String in : new String[]{"?zzz", "?pz", "?xyz", "?pvp x", "?menu q", "?party zzz", "?chat q",
                "?bossaddon zz", "?bossaddon pvp x"}) {
            assertTrue(at(in, in.length()).getList().isEmpty(), "no suggestions for invalid input: " + in);
        }
    }

    // ---- corruption guard: a completion must REPLACE the partial token, never insert after it ------------

    @Test
    void partialTokensSpliceCleanlyWithoutDuplicating() {
        // Completing at end-of-input (what the live dropdown does) must REPLACE the whole partial token, never
        // insert after it and duplicate characters — the garbled-text bug: "?pa" -> "?paparty" instead of
        // "?party", or "?pvp o" -> "?pvp offo" instead of "?pvp off". Verify short and long form.
        assertEquals(List.of("?party"), appliedEnd("?pa"));
        assertEquals(List.of("?party"), appliedEnd("?part"));
        assertEquals(List.of("?pvp"), appliedEnd("?pv"));
        assertEquals(List.of("?party invite"), appliedEnd("?party inv"));
        assertEquals(List.of("?bossaddon party"), appliedEnd("?bossaddon pa"));
        assertTrue(appliedEnd("?pvp o").containsAll(List.of("?pvp on", "?pvp off")), appliedEnd("?pvp o").toString());
        assertTrue(appliedEnd("?bossaddon pvp o").containsAll(List.of("?bossaddon pvp on", "?bossaddon pvp off")),
            appliedEnd("?bossaddon pvp o").toString());

        // General property: completing at end-of-input, the range ends at end-of-input and starts at the token
        // start, so the applied text never repeats the typed partial back-to-back.
        for (String in : new String[]{"?pa", "?part", "?pv", "?pvp o", "?party inv", "?bossaddon pa",
                "?bossaddon pvp o"}) {
            for (Suggestion sug : at(in).getList()) {
                assertEquals(in.length(), sug.getRange().getEnd(), "range must end at end-of-input for: " + in);
                String cleanSplice = in.substring(0, sug.getRange().getStart()) + sug.getText();
                assertEquals(cleanSplice, sug.apply(in), "completion must replace the whole partial for: " + in);
                String partial = in.substring(sug.getRange().getStart(), sug.getRange().getEnd());
                assertFalse(sug.apply(in).contains(partial + partial),
                    "applied text must not duplicate the typed partial: " + sug.apply(in));
            }
        }
    }

    @Test
    void partyInviteAppearsInTheDropdownForBothForms() {
        // "?party " lists the party subcommands including the explicit "invite".
        Suggestions shortForm = at("?party ", 7);
        assertTrue(texts(shortForm).contains("invite"), "party subcommands: " + texts(shortForm));
        assertTrue(applied(shortForm, "?party ").contains("?party invite"), "apply rebuilds the line");

        // Long form "?bossaddon party " offers the same, ranges offset past "?bossaddon ".
        Suggestions longForm = at("?bossaddon party ", 17);
        assertTrue(applied(longForm, "?bossaddon party ").contains("?bossaddon party invite"),
            "long-form invite: " + applied(longForm, "?bossaddon party "));

        // A partial "?party inv" narrows to just "invite", replacing the partial token in place.
        Suggestions partial = at("?party inv", 10);
        assertEquals(List.of("invite"), texts(partial));
        assertEquals(List.of("?party invite"), applied(partial, "?party inv"));
    }
}
