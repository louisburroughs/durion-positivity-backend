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
    @DisplayName("an undefined term reports defined=false without instructing the model to ask")
    void lookup_undefinedTerm() {
        JsonNode node = lookup("our most loyal customers");

        assertThat(node.get("defined").asBoolean()).isFalse();
        assertThat(node.has("metric")).isFalse();
        // #1705: the guidance used to say "Ask the user which measure they mean" unconditionally,
        // which the model applied even when the question named the measure. It must now route the
        // stated-measure case to an answer and reserve asking for the genuinely open one.
        assertThat(node.get("guidance").asText())
                .contains("not by itself a reason to ask")
                .contains("use the measure the question named")
                .contains("Ask only when the question names no measure")
                .contains("Never ask about the date range")
                // Same contamination guard as the layer: the tool's examples must not be corpus
                // utterances, or q01 passing partly measures prompt recall.
                .doesNotContain("top technicians");
    }

    @Test
    @DisplayName("the undefined-term guidance never issues a bare instruction to ask")
    void lookup_undefinedTerm_doesNotIssueABareAskInstruction() {
        // The exact string that produced the q01 regression. Its absence is the fix; asserting on
        // it keeps a future edit from reintroducing the unconditional form.
        assertThat(lookup("our most loyal customers").get("guidance").asText())
                .doesNotContain("Ask the user which measure they mean rather than choosing one");
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

    @Test
    @DisplayName("the INFO log records the outcome but never the caller's text")
    void lookup_doesNotLogTheCallersText() {
        // The term argument is whatever the user wrote and the tool accepts whole questions, so it
        // routinely carries customer and vendor names. The grader needs the outcome, not the text.
        try (LogCapture capture = new LogCapture()) {
            tool.lookupBusinessTerm("who are the best customers at Northgate Freight Ltd?");

            assertThat(capture.events()).isNotEmpty();
            assertThat(capture.events())
                    .allSatisfy(event -> assertThat(event.getFormattedMessage())
                            .doesNotContain("Northgate Freight Ltd")
                            .doesNotContain("who are the"));
            assertThat(capture.events())
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("defined=true")
                            .contains("resolvedTerm=best customers")
                            .contains("glossaryVersion=" + BusinessGlossary.VERSION));
        }
    }

    @Test
    @DisplayName("an undefined lookup logs the outcome without the text that produced it")
    void lookup_undefinedTermIsLoggedWithoutText() {
        try (LogCapture capture = new LogCapture()) {
            tool.lookupBusinessTerm("who are our most loyal customers at Harbor Tool & Die");

            assertThat(capture.events())
                    .allSatisfy(event -> assertThat(event.getFormattedMessage())
                            .doesNotContain("Harbor Tool & Die")
                            .contains("defined=false"));
        }
    }

    /** Captures the tool's own INFO output so the redaction can be asserted rather than assumed. */
    private static final class LogCapture implements AutoCloseable {

        private final ch.qos.logback.classic.Logger logger;
        private final ch.qos.logback.classic.Level previousLevel;
        private final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();

        private LogCapture() {
            this.logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(GlossaryFacadeTool.class);
            this.previousLevel = logger.getLevel();
            logger.setLevel(ch.qos.logback.classic.Level.INFO);
            appender.start();
            logger.addAppender(appender);
        }

        private java.util.List<ch.qos.logback.classic.spi.ILoggingEvent> events() {
            return appender.list;
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            appender.stop();
            logger.setLevel(previousLevel);
        }
    }
}
