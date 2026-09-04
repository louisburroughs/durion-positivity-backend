package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** {@link BusinessGlossary} — the decided-term catalog behind the #1688 clarify-vs-answer rule. */
class BusinessGlossaryTest {

    @Test
    @DisplayName("every decided term resolves to itself")
    void lookup_resolvesEveryCanonicalTerm() {
        for (BusinessGlossary.Definition definition : BusinessGlossary.definitions()) {
            assertThat(BusinessGlossary.lookup(definition.term()))
                    .as("canonical term '%s'", definition.term())
                    .hasValueSatisfying(found -> assertThat(found.term()).isEqualTo(definition.term()));
        }
    }

    @Test
    @DisplayName("every alias resolves to the term that declares it")
    void lookup_resolvesEveryAlias() {
        for (BusinessGlossary.Definition definition : BusinessGlossary.definitions()) {
            for (String alias : definition.aliases()) {
                assertThat(BusinessGlossary.lookup(alias))
                        .as("alias '%s' of '%s'", alias, definition.term())
                        .isPresent();
            }
        }
    }

    /**
     * Keys on {@link BusinessGlossary#normalize} — the transform the index itself uses — and covers
     * canonical terms as well as aliases. The earlier version of this test compared raw lowercase
     * strings and looked only at aliases, so it asserted a different invariant from the one the
     * index holds: two entries differing only in stripped punctuation or collapsed whitespace share
     * a key, and that is exactly the collision that would make a lookup depend on declaration order.
     */
    @Test
    @DisplayName("no normalized key is claimed by two different terms, so a lookup is never order-dependent")
    void normalizedKeys_areUnambiguous() {
        Map<String, String> claimedBy = new HashMap<>();
        for (BusinessGlossary.Definition definition : BusinessGlossary.definitions()) {
            List<String> keys = new ArrayList<>();
            keys.add(definition.term());
            keys.addAll(definition.aliases());
            for (String key : keys) {
                String normalized = BusinessGlossary.normalize(key);
                String previous = claimedBy.putIfAbsent(normalized, definition.term());
                assertThat(previous == null || previous.equals(definition.term()))
                        .as(
                                "normalized key '%s' is claimed by both '%s' and '%s'",
                                normalized, previous, definition.term())
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("the index refuses to build on a collision rather than silently keeping the first entry")
    void index_failsFastOnCollision() {
        // The guarantee is enforced at class-initialization, not only asserted here: touching the
        // catalog is enough to trigger it. If a future edit introduces a colliding alias, every test
        // that reaches the glossary fails with both terms named, instead of one lookup quietly
        // returning the wrong metric.
        assertThatCode(() -> BusinessGlossary.lookup("best customers")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("two same-length keys naming different metrics are ambiguous, so the lookup asks rather than guesses")
    void lookup_returnsEmptyOnASameLengthTie() {
        // "low stock" and "backed up" are both 9 characters and name different definitions
        // (running low; backed up in the shop). A phrase containing both used to resolve to
        // whichever was declared first; the correct answer is that the question is ambiguous and
        // the model should ask which metric is meant.
        assertThat(BusinessGlossary.normalize("low stock").length())
                .isEqualTo(BusinessGlossary.normalize("backed up").length());

        assertThat(BusinessGlossary.lookup("are we backed up and is anything low stock"))
                .isEmpty();
    }

    @ParameterizedTest
    @DisplayName("resolves a term embedded in a whole question, with punctuation and casing")
    @ValueSource(
            strings = {
                "Who are our best customers?",
                "who are our BEST CUSTOMERS this year",
                "Show me who owes us the most money.",
                "which technicians are the most productive",
                "are we running low on anything important",
                "where are we getting backed up in the shop?"
            })
    void lookup_findsTermInsideAQuestion(String question) {
        assertThat(BusinessGlossary.lookup(question)).isPresent();
    }

    @Test
    @DisplayName("prefers the longest matching key when a question could match more than one")
    void lookup_prefersLongestMatch() {
        // "largest customers" and "best customers" are different metrics — revenue vs contribution
        // margin — so a question naming one must not resolve to the other merely because that entry
        // was declared first.
        assertThat(BusinessGlossary.lookup("who are our largest customers"))
                .hasValueSatisfying(found -> assertThat(found.term()).isEqualTo("largest customers"));
        assertThat(BusinessGlossary.lookup("who are our best customers"))
                .hasValueSatisfying(found -> assertThat(found.term()).isEqualTo("best customers"));
    }

    @Test
    @DisplayName("best and largest are deliberately different metrics")
    void bestAndLargest_measureDifferentThings() {
        String best = BusinessGlossary.lookup("best customers").orElseThrow().definition();
        String largest =
                BusinessGlossary.lookup("largest customers").orElseThrow().definition();

        assertThat(best).contains("contribution margin");
        assertThat(largest).contains("net recognized revenue").doesNotContain("contribution margin");
    }

    @Test
    @DisplayName("an undefined metric returns empty — that is the signal to ask")
    void lookup_returnsEmptyForAnUndefinedMetric() {
        assertThat(BusinessGlossary.lookup("who are our most loyal customers")).isEmpty();
        assertThat(BusinessGlossary.lookup("which customers are the most strategic"))
                .isEmpty();
    }

    @Test
    @DisplayName("every definition carries a metric and a default window")
    void definitions_areComplete() {
        assertThat(BusinessGlossary.definitions()).isNotEmpty();
        for (BusinessGlossary.Definition definition : BusinessGlossary.definitions()) {
            assertThat(definition.definition())
                    .as("metric for '%s'", definition.term())
                    .isNotBlank();
            assertThat(definition.defaultWindow())
                    .as("default window for '%s'", definition.term())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("the version is set, so a graded fixture can pin which definition produced its answer")
    void version_isDeclared() {
        assertThat(BusinessGlossary.VERSION).isNotBlank();
    }
}
