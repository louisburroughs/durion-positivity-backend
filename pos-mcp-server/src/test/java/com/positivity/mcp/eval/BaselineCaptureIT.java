package com.positivity.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.service.ToolRegistryService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Gate 0 baseline-capture driver (live). Runs the tool-selection fixtures through the real
 * permission-gated selector ({@link ToolRegistryService#resolveCandidateTools}) and scores them with
 * {@link EvalMetrics} (hit@5, MRR), writing the result to {@code target/eval/baseline-tool-selection.json}.
 *
 * <p>Requires the live stack (Postgres+pgvector with seeded tools/embeddings + the embedding model).
 * Enabled only with {@code -Dmcp.eval.live=true}; it neither starts a context nor runs in offline CI.
 *
 * <pre>
 *   ./mvnw -o -pl pos-mcp-server test -Dtest=BaselineCaptureIT \
 *       -Dmcp.eval.live=true -Dspring.profiles.active=alpha
 * </pre>
 *
 * <p>RAG recall@k capture is a follow-up (needs the retriever + the doc-id metadata key) — see the
 * TODO below; tool-selection hit@5/MRR is the primary Gate 0 baseline.
 */
@SpringBootTest
@ActiveProfiles("alpha")
@EnabledIfSystemProperty(named = "mcp.eval.live", matches = "true")
class BaselineCaptureIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaselineCaptureIT.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path EVAL_ROOT = Paths.get(System.getProperty("user.dir"), "src/test/resources/eval");
    private static final int K = 5;

    @Autowired
    private ToolRegistryService toolRegistryService;

    @Test
    @DisplayName("capture tool-selection hit@5 / MRR baseline against the live selector")
    void captureToolSelectionBaseline() throws Exception {
        List<Double> hits = new ArrayList<>();
        List<Double> reciprocalRanks = new ArrayList<>();
        List<Map.Entry<String, Boolean>> forbiddenViolations = new ArrayList<>();

        for (Path file : suiteFiles("tool-selection")) {
            JsonNode root = MAPPER.readTree(file.toFile());
            for (JsonNode fx : root.get("fixtures")) {
                String id = fx.path("fixture_id").asText();
                String utterance = fx.path("utterance").asText();
                JsonNode actor = fx.get("actor");
                String role = actor.path("role").asText();
                Set<String> permissionCodes = stringSet(actor.get("permission_codes"));
                String workflowState = actor.path("workflow_state").asText("IDLE");
                JsonNode expected = fx.get("expected");
                List<String> expectedTools = stringList(expected.get("tool_ids"));
                Set<String> forbidden = stringSet(expected.get("forbidden_tool_ids"));

                List<ToolMetadata> candidates = toolRegistryService.resolveCandidateTools(
                        new ToolSelectionContext(utterance, role, workflowState, permissionCodes), K);
                List<String> ranked =
                        candidates.stream().map(ToolMetadata::name).toList();

                if (!forbidden.isEmpty() && !EvalMetrics.forbiddenAbsent(ranked, forbidden)) {
                    forbiddenViolations.add(Map.entry(id, true));
                }
                if (!expectedTools.isEmpty()) {
                    String primary = expectedTools.get(0);
                    hits.add(EvalMetrics.hitAtK(ranked, primary, K));
                    reciprocalRanks.add(EvalMetrics.reciprocalRank(ranked, primary));
                }
            }
        }

        double hitAt5 = EvalMetrics.mean(hits);
        double mrr = EvalMetrics.mean(reciprocalRanks);
        String json = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of(
                        "metric",
                        "tool_selection",
                        "fixtures_scored",
                        hits.size(),
                        "hit_at_5",
                        hitAt5,
                        "mrr",
                        mrr,
                        "forbidden_violations",
                        forbiddenViolations.size()));
        Path out = Paths.get(System.getProperty("user.dir"), "target/eval/baseline-tool-selection.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json);
        LOGGER.info("MCP baseline tool-selection hit@5={} mrr={} scored={} -> {}", hitAt5, mrr, hits.size(), out);

        // Security invariant is hard-fail even during baseline capture.
        assertThat(forbiddenViolations)
                .as("forbidden (permission-negative) tools must never be selected")
                .isEmpty();
    }

    private static List<Path> suiteFiles(String suite) throws Exception {
        try (Stream<Path> s = Files.list(EVAL_ROOT.resolve(suite))) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    private static List<String> stringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) {
            arr.forEach(n -> out.add(n.asText()));
        }
        return out;
    }

    private static Set<String> stringSet(JsonNode arr) {
        return new HashSet<>(stringList(arr));
    }
}
