package com.positivity.mcp.internal.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.EvalTurnTrace;
import com.positivity.mcp.internal.domain.EvalTurnTrace.ToolCallTrace;
import com.positivity.mcp.internal.domain.EvalTurnTrace.ToolDefinitionTrace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Offline replay eval (#1682): drives the twelve fixtures in
 * {@code eval/offline-replay/} through
 * a real model via {@link OfflineReplayEvaluator}, with tool execution replaced
 * by each fixture's
 * canned responses. No Spring context, backend service, alpha database, or
 * Eureka registration is
 * involved — only a directly-built {@link ChatModel} (same construction shape
 * as
 * {@code ModelFallbackConfiguration}) and the fixture file.
 *
 * <p>
 * Gated behind {@code -Dmcp.eval.replay=true} so ordinary CI never requires a
 * reachable model.
 * Structural axes (tool selection, call sequence, argument-key presence) are
 * graded here in Java;
 * the answer axis (numbers/id-set/id-order/outcome, prose-fragile checks) is
 * deliberately left to
 * {@code scripts/analytics_gate_run.py --replay-report}, which already carries
 * deterministic,
 * unit-tested grading for those checks (see
 * {@code scripts/test_analytics_gate_run.py}) — this test
 * only produces the report, an {@link EvalTurnTrace} per fixture, that script
 * then grades.
 *
 * <pre>
 *   OLLAMA_CHAT_BASE_URL=... OLLAMA_CHAT_MODEL=... \
 *   ./mvnw -pl pos-mcp-server -Dmcp.eval.replay=true -Dit.test=OfflineReplayEvalIT verify
 *   python3 scripts/analytics_gate_run.py --out /tmp/replay --replay-report \
 *       pos-mcp-server/target/eval/offline-replay-report.json
 * </pre>
 */
@EnabledIfSystemProperty(named = "mcp.eval.replay", matches = "true")
class OfflineReplayEvalIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path FIXTURES_PATH = Paths.get(
            System.getProperty("user.dir"), "src/test/resources/eval/offline-replay/analytics-gate-replay.json");
    private static final Path REPORT_PATH =
            Paths.get(System.getProperty("user.dir"), "target/eval/offline-replay-report.json");
    // 8-4-4-4-12. The last group encodes the issue number (1682) and must be exactly twelve hex
    // digits: the earlier value carried thirteen, so UUID.fromString threw "UUID string too large"
    // in the static initializer and the whole IT failed on class load, before any fixture ran.
    private static final UUID SYNTHETIC_USER_ID = UUID.fromString("01960010-0000-7000-8000-000000001682");

    @Test
    void replayEveryFixtureAgainstTheConfiguredModel() throws IOException {
        ChatModel chatModel = buildChatModel();
        JsonNode fixtures = MAPPER.readTree(FIXTURES_PATH.toFile()).get("fixtures");

        List<Map<String, Object>> reportResults = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (JsonNode fixture : fixtures) {
            String fixtureId = fixture.path("fixture_id").asText();
            FixtureSetup setup = buildFixtureSetup(fixture);
            OfflineReplayEvaluator.ReplayResult result = OfflineReplayEvaluator.replay(
                    chatModel,
                    fixture.path("system_prompt").asText(),
                    fixture.path("utterance").asText(),
                    setup.toolCallbacks(),
                    setup.observedCalls());

            reportResults.add(toReportEntry(fixtureId, fixture, setup, result));
            failures.addAll(gradeStructuralAxes(fixtureId, fixture, result));
        }

        writeReport(reportResults);

        if (!failures.isEmpty()) {
            throw new AssertionError(
                    "offline replay structural grading failed:\n  - " + String.join("\n  - ", failures));
        }
    }

    /**
     * The stub tools for one fixture, plus the single mutable sink they all append to.
     *
     * <p>{@code observedCalls} is carried here rather than created by the caller because the stubs
     * are the only writers: a caller that built its own list would hand {@code replay} an empty one
     * and get an empty {@link OfflineReplayEvaluator.ReplayResult#toolCalls()} back, which silently
     * empties every structural axis — tool selection, call sequence and argument accuracy all read
     * that list. Keeping the sink with the callbacks that write to it means there is no second list
     * in scope to pass by mistake.
     */
    record FixtureSetup(
            @NonNull List<ToolCallback> toolCallbacks,
            @NonNull List<ToolDefinitionTrace> offeredTools,
            @NonNull List<OfflineReplayEvaluator.ObservedToolCall> observedCalls) {}

    static @NonNull FixtureSetup buildFixtureSetup(@NonNull JsonNode fixture) {
        Map<String, Deque<OfflineReplayEvaluator.ToolResponseFixture>> responsesByTool = new LinkedHashMap<>();
        for (JsonNode canned : fixture.path("tool_responses")) {
            String toolName = canned.path("tool_name").asText();
            List<String> argumentsContains = new ArrayList<>();
            canned.path("arguments_contains").forEach(node -> argumentsContains.add(node.asText()));
            responsesByTool
                    .computeIfAbsent(toolName, ignored -> OfflineReplayEvaluator.newResponseQueue())
                    .add(new OfflineReplayEvaluator.ToolResponseFixture(
                            toolName, argumentsContains, canned.path("response").asText()));
        }

        List<ToolCallback> toolCallbacks = new ArrayList<>();
        List<ToolDefinitionTrace> offeredTools = new ArrayList<>();
        // The observed-call sink is created once per stub tool below so every stub for
        // this fixture
        // appends to the SAME list, giving one true call-order sequence across all
        // offered tools.
        List<OfflineReplayEvaluator.ObservedToolCall> sharedObservedCalls = new ArrayList<>();
        for (JsonNode tool : fixture.path("offered_tools")) {
            String name = tool.path("name").asText();
            String description = tool.path("description").asText();
            String inputSchema = tool.path("input_schema").asText();
            offeredTools.add(new ToolDefinitionTrace(name, description, inputSchema));
            toolCallbacks.add(OfflineReplayEvaluator.stubCallback(
                    name,
                    description,
                    inputSchema,
                    responsesByTool.computeIfAbsent(name, ignored -> OfflineReplayEvaluator.newResponseQueue()),
                    sharedObservedCalls));
        }
        return new FixtureSetup(toolCallbacks, offeredTools, sharedObservedCalls);
    }

    private static @NonNull List<String> gradeStructuralAxes(
            @NonNull String fixtureId, @NonNull JsonNode fixture, OfflineReplayEvaluator.@NonNull ReplayResult result) {
        List<String> failures = new ArrayList<>();
        List<String> expectedSequence = new ArrayList<>();
        fixture.path("expected").path("tool_call_sequence").forEach(node -> expectedSequence.add(node.asText()));
        List<String> observedSequence = result.toolCalls().stream()
                .map(OfflineReplayEvaluator.ObservedToolCall::name)
                .toList();

        if (!observedSequence.equals(expectedSequence)) {
            Set<String> expectedSet = new LinkedHashSet<>(expectedSequence);
            Set<String> observedSet = new LinkedHashSet<>(observedSequence);
            String axis = expectedSet.equals(observedSet) ? "tool_call_sequence" : "tool_selection";
            failures.add(
                    "%s[%s]: expected %s, observed %s".formatted(fixtureId, axis, expectedSequence, observedSequence));
        }
        for (OfflineReplayEvaluator.ObservedToolCall call : result.toolCalls()) {
            if (call.argumentsMatched()) {
                continue;
            }
            // A stub records argumentsMatched=false for BOTH a genuine argument mismatch and a call
            // it could not serve at all (an exhausted response queue, say). Reporting the second as
            // "missing a required argument key" sends the reader looking at the model's arguments
            // when the fixture is what ran out, so the two are named separately.
            if (call.error() != null) {
                failures.add("%s[tool_error]: call #%d to '%s' could not be served by the fixture: %s"
                        .formatted(fixtureId, call.sequence(), call.name(), call.error()));
                continue;
            }
            failures.add("%s[argument_accuracy]: call #%d to '%s' missing a required argument key: %s"
                    .formatted(fixtureId, call.sequence(), call.name(), call.arguments()));
        }
        return failures;
    }

    private static @NonNull Map<String, Object> toReportEntry(
            @NonNull String fixtureId,
            @NonNull JsonNode fixture,
            @NonNull FixtureSetup setup,
            OfflineReplayEvaluator.@NonNull ReplayResult result) {
        Instant now = Instant.now();
        List<ToolCallTrace> toolCallTraces = result.toolCalls().stream()
                .map(call -> new ToolCallTrace(
                        call.sequence(), call.name(), call.arguments(), call.result(), call.error(), 0))
                .toList();
        EvalTurnTrace trace = new EvalTurnTrace(
                UUID.randomUUID(),
                now,
                now,
                now.plus(Duration.ofDays(1)),
                SYNTHETIC_USER_ID,
                "offline-replay",
                "offline-replay",
                fixture.path("utterance").asText(),
                false,
                fixture.path("expected").path("router_intent").asText(null),
                fixture.path("expected").path("model_tier").asText(null),
                null,
                setup.offeredTools().stream().map(ToolDefinitionTrace::name).toList(),
                fixture.path("system_prompt").asText(),
                setup.offeredTools(),
                toolCallTraces,
                result.finalResponse(),
                result.error());
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("fixture_id", fixtureId);
        entry.put("trace", trace);
        return entry;
    }

    private static void writeReport(@NonNull List<Map<String, Object>> results) throws IOException {
        Files.createDirectories(REPORT_PATH.getParent());
        Map<String, Object> report = Map.of("results", results);
        Files.writeString(REPORT_PATH, MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    /**
     * Same construction shape as {@code ModelFallbackConfiguration}; no Spring
     * context required.
     */
    private static @NonNull ChatModel buildChatModel() {
        String baseUrl = env("OLLAMA_CHAT_BASE_URL", "https://ollama.com");
        String modelName = env("OLLAMA_CHAT_MODEL", "gpt-oss:120b");
        String apiKey = env("OLLAMA_API_KEY", "");
        int timeoutMillis = Math.toIntExact(
                Duration.parse("PT" + env("OLLAMA_CHAT_TIMEOUT", "180S").toUpperCase())
                        .toMillis());
        double temperature = Double.parseDouble(env("OLLAMA_CHAT_TEMPERATURE", "0.0"));
        int numCtx = Integer.parseInt(env("OLLAMA_NUM_CTX", "32768"));

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(requestFactory);
        if (!apiKey.isBlank()) {
            restClientBuilder.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey);
        }
        OllamaApi ollamaApi = OllamaApi.builder()
                .baseUrl(baseUrl)
                .restClientBuilder(restClientBuilder)
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(OllamaChatOptions.builder()
                        .model(modelName)
                        .temperature(temperature)
                        .numCtx(numCtx)
                        .build())
                .build();
    }

    private static @NonNull String env(@NonNull String name, @NonNull String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
