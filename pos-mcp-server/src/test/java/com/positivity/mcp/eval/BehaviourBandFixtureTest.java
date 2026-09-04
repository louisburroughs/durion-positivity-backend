package com.positivity.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Structural guard for the behaviour-band fixtures (#1689).
 *
 * <p>These are the capability bands the analytics corpus could not express: questions whose correct
 * answer is an OUTCOME rather than a number. They live in their own file because every question in
 * {@code QUESTIONS.json} carries {@code ground_truth_sql} and an {@code EXPECTED.md} section, and
 * these have no numeric ground truth by construction.
 *
 * <p>The band that matters most is {@code impossible}. Until it existed there was no fixture
 * anywhere that a decline could pass, so an assistant that correctly refused an unanswerable
 * question scored identically to one that failed — which is why q04 and q08 deflecting could never
 * be told apart from genuine failures.
 */
class BehaviourBandFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path BANDS =
            Paths.get(System.getProperty("user.dir"), "src/test/resources/eval/analytics-gate/BEHAVIOUR_BANDS.json");

    private static JsonNode document() throws IOException {
        return MAPPER.readTree(Files.readString(BANDS));
    }

    @Test
    @DisplayName("every fixture declares a unique id, a band, an outcome and a rationale")
    void fixturesAreStructurallyComplete() throws IOException {
        JsonNode document = document();
        Set<String> declaredOutcomes = new HashSet<>();
        document.get("outcomes").fieldNames().forEachRemaining(declaredOutcomes::add);
        assertThat(declaredOutcomes).isNotEmpty();

        Set<String> ids = new HashSet<>();
        for (JsonNode question : document.get("questions")) {
            String id = question.path("fixture_id").asText();
            assertThat(id).as("fixture_id must be present").isNotBlank();
            assertThat(ids.add(id)).as("duplicate fixture_id %s", id).isTrue();
            assertThat(question.path("band").asText()).as("%s band", id).isNotBlank();
            assertThat(question.path("utterance").asText())
                    .as("%s utterance", id)
                    .isNotBlank();
            // The rationale is what stops a future reader "fixing" a fixture whose expected outcome
            // looks wrong at a glance — every one of these encodes a contract decision.
            assertThat(question.path("rationale").asText())
                    .as("%s must say why its outcome is the correct one", id)
                    .isNotBlank();
            assertThat(declaredOutcomes)
                    .as("%s declares an outcome the suite does not define", id)
                    .contains(question.path("expected_outcome").asText());
        }
    }

    @Test
    @DisplayName("the bands #1689 names as missing are all present")
    void theMissingBandsArePresent() throws IOException {
        Set<String> bands = new HashSet<>();
        for (JsonNode question : document().get("questions")) {
            bands.add(question.path("band").asText());
        }

        // "impossible" and "noisy" are the two #1689 declares and never delivered.
        assertThat(bands).contains("impossible", "noisy", "ambiguous-metric");
    }

    @Test
    @DisplayName("both sides of the clarifying-question rule are covered")
    void bothSidesOfTheAskRuleAreCovered() throws IOException {
        Set<String> outcomesByBand = new HashSet<>();
        for (JsonNode question : document().get("questions")) {
            outcomesByBand.add(question.path("band").asText() + "="
                    + question.path("expected_outcome").asText());
        }

        // A glossary that only ever produces questions is as broken as one that never does, so the
        // corpus has to pin both directions (#1688, #1705).
        assertThat(outcomesByBand).contains("ambiguous-metric=asked", "defined-metric-must-not-ask=answered");
        // And the same for ranges: unstated is not a reason to ask (#1681); unconventional is.
        assertThat(outcomesByBand)
                .contains("unstated-range-must-not-ask=answered", "unconventional-range-must-ask=asked");
    }

    @Test
    @DisplayName("a decline is a passing outcome for at least one fixture")
    void aDeclineCanPass() throws IOException {
        long declines = 0;
        for (JsonNode question : document().get("questions")) {
            if ("declined".equals(question.path("expected_outcome").asText())) {
                declines++;
            }
        }

        // The gap this file was created to close: with no such fixture, a correct refusal and a
        // genuine failure score the same.
        assertThat(declines).isGreaterThanOrEqualTo(3);
    }
}
