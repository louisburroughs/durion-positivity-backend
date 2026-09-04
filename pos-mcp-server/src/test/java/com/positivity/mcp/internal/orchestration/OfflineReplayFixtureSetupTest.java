package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the fixture-to-stub wiring in {@link OfflineReplayEvalIT}.
 *
 * <p>This exists because {@code OfflineReplayEvalIT} is {@code @EnabledIfSystemProperty("mcp.eval.replay")}
 * and builds a real {@link org.springframework.ai.ollama.OllamaChatModel}, so it never runs in CI —
 * which is how the defect this pins shipped green. The stubs appended every call to a list the
 * {@code FixtureSetup} did not expose, while {@code replay} was handed a separate empty one, so
 * {@code ReplayResult.toolCalls()} came back empty for every fixture and all three structural axes
 * (tool selection, call sequence, argument accuracy) graded nothing at all.
 *
 * <p>The bug is now unrepresentable — there is only one list in scope — but "unrepresentable" is a
 * property of the current shape, not a guarantee about the next edit, and nothing else that runs in
 * CI touches this path.
 */
class OfflineReplayFixtureSetupTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FIXTURE = """
            {
              "fixture_id": "wiring-probe",
              "utterance": "probe",
              "system_prompt": "probe",
              "offered_tools": [
                {"name": "alpha", "description": "first stub", "input_schema": "{}"},
                {"name": "beta", "description": "second stub", "input_schema": "{}"}
              ],
              "tool_responses": [
                {"tool_name": "alpha", "arguments_contains": [], "response": "{\\"ok\\":1}"},
                {"tool_name": "beta", "arguments_contains": [], "response": "{\\"ok\\":2}"}
              ]
            }
            """;

    private static JsonNode fixture() {
        try {
            return MAPPER.readTree(FIXTURE);
        } catch (Exception exception) {
            throw new AssertionError("fixture is not valid JSON", exception);
        }
    }

    @Test
    @DisplayName("the setup exposes the very list its stub callbacks append to")
    void fixtureSetup_exposesTheSinkItsStubsWriteTo() {
        OfflineReplayEvalIT.FixtureSetup setup = OfflineReplayEvalIT.buildFixtureSetup(fixture());

        assertThat(setup.toolCallbacks()).hasSize(2);
        assertThat(setup.observedCalls()).isEmpty();

        setup.toolCallbacks().get(0).call("{}");

        // The assertion that would have failed: with a sink the setup does not expose, this list is
        // a different instance and stays empty however many tools the model calls.
        assertThat(setup.observedCalls())
                .as("stub calls must land in the list the setup hands to replay()")
                .hasSize(1);
        assertThat(setup.observedCalls().get(0).name()).isEqualTo("alpha");
    }

    @Test
    @DisplayName("all stubs for one fixture share a single call sequence, in call order")
    void fixtureSetup_sharesOneSequenceAcrossStubs() {
        OfflineReplayEvalIT.FixtureSetup setup = OfflineReplayEvalIT.buildFixtureSetup(fixture());

        setup.toolCallbacks().get(1).call("{}");
        setup.toolCallbacks().get(0).call("{}");

        assertThat(setup.observedCalls())
                .extracting(OfflineReplayEvaluator.ObservedToolCall::name)
                .containsExactly("beta", "alpha");
        assertThat(setup.observedCalls())
                .extracting(OfflineReplayEvaluator.ObservedToolCall::sequence)
                .containsExactly(1, 2);
    }
}
