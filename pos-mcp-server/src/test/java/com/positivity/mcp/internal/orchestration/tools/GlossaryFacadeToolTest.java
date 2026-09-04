package com.positivity.mcp.internal.orchestration.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link GlossaryFacadeTool} — the tool-boundary contract the #1682 per-stage grader asserts on. */
class GlossaryFacadeToolTest {

    private final GlossaryFacadeTool tool = new GlossaryFacadeTool();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode lookup(String term) {
        try {
            return MAPPER.readTree(tool.lookupBusinessTerm(term));
        } catch (JsonProcessingException exception) {
            throw new AssertionError("tool returned non-JSON", exception);
        }
    }

    @Test
    @DisplayName("a defined term returns its metric, default window and an answer instruction")
    void lookup_definedTerm() {
        JsonNode node = lookup("best customers");

        assertThat(node.get("defined").asBoolean()).isTrue();
        assertThat(node.get("term").asText()).isEqualTo("best customers");
        assertThat(node.get("metric").asText()).contains("contribution margin");
        assertThat(node.get("defaultWindow").asText()).contains("Trailing 12 complete calendar months");
        assertThat(node.get("guidance").asText()).contains("Apply this metric");
    }

    @Test
    @DisplayName("an undefined term reports defined=false and tells the model to ask, not to choose")
    void lookup_undefinedTerm() {
        JsonNode node = lookup("our most loyal customers");

        assertThat(node.get("defined").asBoolean()).isFalse();
        assertThat(node.has("metric")).isFalse();
        assertThat(node.get("guidance").asText())
                .contains("Ask the user which measure")
                .contains("Do not ask about the date range");
    }

    @Test
    @DisplayName("every response carries the glossary version, so a graded fixture can pin the definition used")
    void lookup_alwaysCarriesVersion() {
        assertThat(lookup("best customers").get("glossaryVersion").asText()).isEqualTo(BusinessGlossary.VERSION);
        assertThat(lookup("something undefined").get("glossaryVersion").asText())
                .isEqualTo(BusinessGlossary.VERSION);
    }

    @Test
    @DisplayName("the query is echoed back verbatim, including casing and punctuation")
    void lookup_echoesQuery() {
        assertThat(lookup("Who are our BEST customers?").get("query").asText())
                .isEqualTo("Who are our BEST customers?");
    }

    @Test
    @DisplayName("a whole question resolves, not only the bare phrase")
    void lookup_resolvesFromAWholeQuestion() {
        assertThat(lookup("which of our customers isn't paying on time?")
                        .get("defined")
                        .asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("an undefined metric is never answered from a neighbouring definition")
    void lookup_doesNotGuessANeighbouringDefinition() {
        // "most profitable" is not a decided term. It is adjacent to "best customers" (which is a
        // margin measure) and a fuzzy match would silently answer it on that definition — exactly
        // the confident-but-uncheckable outcome the glossary exists to prevent.
        JsonNode node = lookup("who are our most profitable customers");

        assertThat(node.get("defined").asBoolean()).isFalse();
    }
}
