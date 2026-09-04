package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
                // Presence alone would pass for an alias that resolved to a DIFFERENT definition
                // through the containment fallback, which is the failure mode worth guarding.
                assertThat(BusinessGlossary.lookup(alias))
                        .as("alias '%s' of '%s'", alias, definition.term())
                        .hasValueSatisfying(found -> assertThat(found.term()).isEqualTo(definition.term()));
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
        // Exercised on a hand-built catalog: asserting only that the SHIPPED catalog builds would
        // duplicate normalizedKeys_areUnambiguous and would pass just as happily over the old
        // putIfAbsent implementation this replaced.
        List<BusinessGlossary.Definition> colliding = List.of(
                new BusinessGlossary.Definition("best customers", "margin", "trailing 12 months", Set.of()),
                new BusinessGlossary.Definition(
                        "largest customers", "revenue", "trailing 12 months", Set.of("Best Customers?")));

        assertThatThrownBy(() -> BusinessGlossary.index(colliding))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("best customers")
                .hasMessageContaining("largest customers");
    }

    @Test
    @DisplayName("an alias normalizing onto its own canonical term is allowed, not a collision")
    void index_allowsSameDefinitionRepeats() {
        List<BusinessGlossary.Definition> repeated = List.of(new BusinessGlossary.Definition(
                "best customers", "margin", "trailing 12 months", Set.of("Best Customers!")));

        assertThatCode(() -> BusinessGlossary.index(repeated)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a question naming two different metrics is ambiguous whatever their key lengths")
    void lookup_returnsEmptyWhenTwoDifferentMetricsAreNamed() {
        // The near-tie case: "payment problems" (16) and "who owes us money" (17) name different
        // metrics, so resolving by longest key would decide a business question on one character of
        // spelling and answer half of it on the wrong definition.
        assertThat(BusinessGlossary.lookup("which customers have payment problems and who owes us money"))
                .isEmpty();
    }

    @Test
    @DisplayName("a curly apostrophe resolves the same as an ASCII one")
    void lookup_foldsTypographicApostrophe() {
        assertThat(BusinessGlossary.lookup("which customers haven\u2019t been back recently"))
                .hasValueSatisfying(found -> assertThat(found.term()).isEqualTo("haven't been back recently"));
        assertThat(BusinessGlossary.lookup("who isn\u2019t paying on time")).isPresent();
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
