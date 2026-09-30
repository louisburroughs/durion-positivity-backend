package com.positivity.mcp.internal.scopegraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.scopegraph.ScopeSet.Seed;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Spec §2.7: how a message seeds entities, and how each seed was recognised. */
class TermMatcherTest {

    private final TermMatcher matcher = TermMatcher.of(ScopeResolverFixtures.graph());

    private List<Seed> seeds(String message) {
        return matcher.match(message);
    }

    @Test
    @DisplayName("an identifier pattern seeds its entity as IDENTIFIER")
    void identifierPattern() {
        assertThat(seeds("what is the status of WO-20391?"))
                .containsExactly(new Seed("workorder", MatchKind.IDENTIFIER));
    }

    @Test
    @DisplayName("a term as written, on word boundaries and whatever its case, seeds as EXACT_TERM")
    void exactTerm() {
        assertThat(seeds("Show me the INVOICE for Acme")).containsExactly(new Seed("invoice", MatchKind.EXACT_TERM));
        // Not inside another word.
        assertThat(seeds("the invoiced amount and the preinvoice run")).isEmpty();
    }

    @Test
    @DisplayName("a plural or an unaccented spelling matches only after folding, and seeds as FOLDED_TERM")
    void foldedTerm() {
        assertThat(seeds("list the open invoices")).containsExactly(new Seed("invoice", MatchKind.FOLDED_TERM));
        assertThat(seeds("ouvrir un ordre de reparation"))
                .containsExactly(new Seed("workorder", MatchKind.FOLDED_TERM));
        assertThat(seeds("todas las ordenes de trabajo")).containsExactly(new Seed("workorder", MatchKind.FOLDED_TERM));
        assertThat(seeds("les bons de travail en retard"))
                .containsExactly(new Seed("workorder", MatchKind.FOLDED_TERM));
    }

    @Test
    @DisplayName("fr and es terms match as written with their accents, as EXACT_TERM")
    void accentedTermsMatchExactly() {
        assertThat(seeds("Ouvrir un ordre de réparation")).containsExactly(new Seed("workorder", MatchKind.EXACT_TERM));
        assertThat(seeds("crear una orden de reparación")).containsExactly(new Seed("workorder", MatchKind.EXACT_TERM));
        assertThat(seeds("quelle PIÈCE faut-il ?")).containsExactly(new Seed("part", MatchKind.EXACT_TERM));
        // A decomposed e + combining grave is the same word as the precomposed one.
        assertThat(seeds("quelle pièce faut-il ?")).containsExactly(new Seed("part", MatchKind.EXACT_TERM));
    }

    @Test
    @DisplayName("a term that denotes several entities seeds all of them as AMBIGUOUS_TERM")
    void ambiguousTerm() {
        assertThat(seeds("open the ticket"))
                .containsExactly(
                        new Seed("invoice", MatchKind.AMBIGUOUS_TERM), new Seed("workorder", MatchKind.AMBIGUOUS_TERM));
    }

    @Test
    @DisplayName(
            "a seed that comes only from a glossary phrase is GLOSSARY_TERM; a phrase naming no entity seeds nothing")
    void glossaryPhrase() {
        assertThat(seeds("who are our best customers?")).containsExactly(new Seed("customer", MatchKind.GLOSSARY_TERM));
        assertThat(seeds("who owes us the most money")).isEmpty();
    }

    @Test
    @DisplayName("longest match wins: a term inside a longer matched term does not seed")
    void longestMatchWins() {
        // "order" is a term of its own, but here it is the tail of "work order".
        assertThat(seeds("close the work order")).containsExactly(new Seed("workorder", MatchKind.EXACT_TERM));
        // The longer match may itself be a folded one.
        assertThat(seeds("close the work orders")).containsExactly(new Seed("workorder", MatchKind.FOLDED_TERM));
        // Outside the longer term, the shorter one still seeds.
        assertThat(seeds("the work order and the order it came from"))
                .containsExactly(new Seed("order", MatchKind.EXACT_TERM), new Seed("workorder", MatchKind.EXACT_TERM));
    }

    @Test
    @DisplayName("an entity recognised several ways records the strongest")
    void strongestKindPerEntity() {
        assertThat(seeds("invoices: which invoice is overdue?"))
                .containsExactly(new Seed("invoice", MatchKind.EXACT_TERM));
        assertThat(seeds("work orders like WO-20391")).containsExactly(new Seed("workorder", MatchKind.IDENTIFIER));
    }

    @Test
    @DisplayName("a multi-line message matches on every line, and a term may span a line break")
    void multiLineMessage() {
        assertThat(seeds("Hi,\n\nI need the estimate\nand the invoice.\nThanks"))
                .containsExactly(new Seed("estimate", MatchKind.EXACT_TERM), new Seed("invoice", MatchKind.EXACT_TERM));
        assertThat(seeds("please close the work\norder today"))
                .containsExactly(new Seed("workorder", MatchKind.EXACT_TERM));
        assertThat(seeds("first line\r\nWO-20391\r\nlast line"))
                .containsExactly(new Seed("workorder", MatchKind.IDENTIFIER));
    }

    @Test
    @DisplayName("folding does not invent words: a short stem is not a plural of something else")
    void foldingIsBounded() {
        // "prix" is a term; "pris" and "pri" are different words.
        assertThat(seeds("j'ai pris le pri")).isEmpty();
        assertThat(seeds("quel est le prix ?")).containsExactly(new Seed("part", MatchKind.EXACT_TERM));
    }

    @Test
    @DisplayName("a message naming nothing, an empty message and an empty graph seed nothing")
    void nothingToSeed() {
        assertThat(seeds("what time do you close on Saturdays?")).isEmpty();
        assertThat(seeds("")).isEmpty();
        assertThat(TermMatcher.of(ScopeGraph.empty()).match("the work order")).isEmpty();
    }

    @Test
    @DisplayName("only the head of a very long message is scanned")
    void longMessageIsBounded() {
        String padding = "x ".repeat(TermMatcher.MAX_SCANNED_CHARS);
        assertThat(seeds(padding + "invoice")).isEmpty();
        assertThat(seeds("invoice " + padding)).containsExactly(new Seed("invoice", MatchKind.EXACT_TERM));
    }

    @Test
    @DisplayName("fold keeps the length of its input, so a position means the same in both passes")
    void foldPreservesLength() {
        String text = "Pièce DÉTACHÉE œuvre İ";
        assertThat(TermMatcher.fold(text)).hasSameSizeAs(text).startsWith("piece detachee");
    }
}
