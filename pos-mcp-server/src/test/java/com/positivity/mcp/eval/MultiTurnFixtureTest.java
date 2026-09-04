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
 * Structural guard for the multi-turn sequence fixtures (#1690).
 *
 * <p>Multi-turn was entirely unmeasured: {@code mcp.agent.memory-max-messages} is 100 and no test
 * or fixture exercised a follow-up. These sequences are runnable against the existing chat path
 * with no API change, because {@code SessionAgentManager} keys chat memory on
 * {@code (username, role)} — consecutive calls with the same token already share a conversation.
 *
 * <p>That same property is why the single-turn gate is not actually single-turn, which is a
 * separate defect tracked in #1735.
 */
class MultiTurnFixtureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path SEQUENCES =
            Paths.get(System.getProperty("user.dir"), "src/test/resources/eval/analytics-gate/MULTI_TURN.json");

    private static JsonNode document() throws IOException {
        return MAPPER.readTree(Files.readString(SEQUENCES));
    }

    @Test
    @DisplayName("every sequence has a unique id and at least two turns")
    void sequencesAreWellFormed() throws IOException {
        Set<String> ids = new HashSet<>();
        for (JsonNode sequence : document().get("sequences")) {
            String id = sequence.path("sequence_id").asText();
            assertThat(id).isNotBlank();
            assertThat(ids.add(id)).as("duplicate sequence_id %s", id).isTrue();
            // One turn is not a sequence; the whole point is what survives between turns.
            assertThat(sequence.path("turns").size())
                    .as("%s must have at least two turns", id)
                    .isGreaterThanOrEqualTo(2);
        }
        assertThat(ids).isNotEmpty();
    }

    @Test
    @DisplayName("every follow-up turn declares what it must reference and what makes it fail")
    void followUpTurnsAreGradable() throws IOException {
        for (JsonNode sequence : document().get("sequences")) {
            String id = sequence.path("sequence_id").asText();
            JsonNode turns = sequence.get("turns");
            for (int i = 1; i < turns.size(); i++) {
                JsonNode turn = turns.get(i);
                // Without both of these a follow-up cannot be scored as a sequence — only as a
                // question that happens to have been asked second, which is the gap #1690 names.
                assertThat(turn.path("must_reference").asText())
                        .as("%s turn %d must say what it has to carry", id, i + 1)
                        .isNotBlank();
                assertThat(turn.path("fails_if").asText())
                        .as("%s turn %d must say what a context loss looks like", id, i + 1)
                        .isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("every declared carry kind is exercised by at least one sequence")
    void everyCarryKindIsCovered() throws IOException {
        JsonNode document = document();
        Set<String> declared = new HashSet<>();
        document.get("carries").fieldNames().forEachRemaining(declared::add);

        Set<String> used = new HashSet<>();
        for (JsonNode sequence : document.get("sequences")) {
            sequence.get("carries").forEach(carry -> used.add(carry.asText()));
        }

        // A declared kind with no sequence behind it is documentation, not coverage.
        assertThat(used).containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    @DisplayName("carry kinds named by a sequence are all declared in the suite")
    void noUndeclaredCarryKinds() throws IOException {
        JsonNode document = document();
        Set<String> declared = new HashSet<>();
        document.get("carries").fieldNames().forEachRemaining(declared::add);

        for (JsonNode sequence : document.get("sequences")) {
            for (JsonNode carry : sequence.get("carries")) {
                assertThat(declared)
                        .as(
                                "%s names an undeclared carry kind %s",
                                sequence.path("sequence_id").asText(), carry)
                        .contains(carry.asText());
            }
        }
    }
}
