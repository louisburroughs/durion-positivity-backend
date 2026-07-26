package com.positivity.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.ToolMetadata;
import com.positivity.mcp.internal.domain.ToolSelectionContext;
import com.positivity.mcp.internal.orchestration.rag.QueryDocumentRetriever;
import com.positivity.mcp.internal.orchestration.rag.ScopedContentRetrieverFactory;
import com.positivity.mcp.internal.orchestration.retrieval.PermissionAwareMetadataFilter;
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
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Gate 0 baseline-capture driver (live). Runs the tool-selection fixtures through the real
 * permission-gated selector ({@link ToolRegistryService#resolveCandidateTools}) and scores them with
 * {@link EvalMetrics} (hit@5, MRR), writing the result to {@code target/eval/baseline-tool-selection.json}.
 *
 * <p>Requires the live stack (Postgres+pgvector with seeded tools/embeddings + the embedding model).
 * Enabled only with {@code -Dmcp.eval.live=true}; disabled by default, so offline CI never starts a
 * context. Runs read-only: Flyway is disabled (no migrations applied to the target schema), and the
 * web server, Eureka client, and permission registration are off — so only the alpha Postgres/pgvector
 * and the embedding model need to be reachable. Required env vars: {@code POS_MCP_DB_HOST},
 * {@code POS_MCP_DB_PASSWORD} (and {@code POS_MCP_DB_USER} if not {@code pos_mcp}), and
 * {@code OLLAMA_EMBEDDING_BASE_URL}.
 *
 * <pre>
 *   POS_MCP_DB_HOST=... POS_MCP_DB_PASSWORD=... OLLAMA_EMBEDDING_BASE_URL=... \
 *   ./mvnw -pl pos-mcp-server test -Dtest=BaselineCaptureIT \
 *       -Dmcp.eval.live=true -Dspring.profiles.active=alpha
 * </pre>
 *
 * <p>Captures two baselines: tool-selection hit@5/MRR ({@link #captureToolSelectionBaseline()}) and
 * RAG recall@k ({@link #captureRagRecallBaseline()}, #783 AC3) through the real scope- and
 * permission-filtered retriever ({@link ScopedContentRetrieverFactory} +
 * {@link PermissionAwareMetadataFilter}).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "spring.flyway.enabled=false",
            "pos.security.permission-registration.enabled=false",
            "eureka.client.enabled=false"
        })
@ActiveProfiles("alpha")
@EnabledIfSystemProperty(named = "mcp.eval.live", matches = "true")
class BaselineCaptureIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaselineCaptureIT.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path EVAL_ROOT = Paths.get(System.getProperty("user.dir"), "src/test/resources/eval");
    private static final int K = 5;

    private static final String DOCUMENT_ID = "document_id";
    private static final String AUTHENTICATED = "AUTHENTICATED";

    @Autowired
    private ToolRegistryService toolRegistryService;

    @Autowired
    private ScopedContentRetrieverFactory scopedContentRetrieverFactory;

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

        // AC4 (#783) quality floors — calibrated from the live baseline (hit@5 0.84 / MRR 0.77),
        // set with margin below observed. Override with -Dmcp.eval.min-hit5 / -Dmcp.eval.min-mrr.
        double minHit5 = Double.parseDouble(System.getProperty("mcp.eval.min-hit5", "0.75"));
        double minMrr = Double.parseDouble(System.getProperty("mcp.eval.min-mrr", "0.65"));
        assertThat(hitAt5).as("tool-selection hit@5 floor").isGreaterThanOrEqualTo(minHit5);
        assertThat(mrr).as("tool-selection MRR floor").isGreaterThanOrEqualTo(minMrr);
    }

    @Test
    @DisplayName("capture RAG recall@k baseline against the live scope+permission-filtered retriever")
    void captureRagRecallBaseline() throws Exception {
        List<Double> recalls = new ArrayList<>();
        List<Map.Entry<String, Boolean>> forbiddenViolations = new ArrayList<>();
        int scored = 0;

        // Match production's cosine similarity floor so recall@k isn't over-reported: the assistant's
        // scoped retrievers use 0.6 (primary) / 0.55 (broad) in SessionAgentManager. Score at the
        // loosest value a doc must clear to enter the pipeline (0.55). Override -Dmcp.eval.rag-min-score.
        double ragMinScore = Double.parseDouble(System.getProperty("mcp.eval.rag-min-score", "0.55"));

        for (Path file : suiteFiles("rag-retrieval")) {
            JsonNode root = MAPPER.readTree(file.toFile());
            for (JsonNode fx : root.get("fixtures")) {
                String id = fx.path("fixture_id").asText();
                String query = fx.path("query").asText();
                JsonNode actor = fx.get("actor");
                Set<String> permissionCodes = new HashSet<>(stringSet(actor.get("permission_codes")));
                permissionCodes.add(AUTHENTICATED); // any authenticated caller carries the synthetic code
                String scope = fx.path("rag_scope").asText("master");
                JsonNode expected = fx.get("expected");
                List<String> expectedDocs = stringList(expected.get("doc_ids"));
                Set<String> forbidden = stringSet(expected.get("forbidden_doc_ids"));
                int k = expected.path("k").asInt(K);

                // Reproduce the production RAG path: scope-filtered ANN + permission-aware visibility.
                QueryDocumentRetriever retriever = new PermissionAwareMetadataFilter(
                        scopedContentRetrieverFactory.create(scope, Math.max(k * 10, 50), ragMinScore),
                        permissionCodes);
                List<String> docs = distinctDocIds(retriever.retrieve(query));
                List<String> topK = docs.subList(0, Math.min(k, docs.size()));

                if (!forbidden.isEmpty() && topK.stream().anyMatch(forbidden::contains)) {
                    forbiddenViolations.add(Map.entry(id, true));
                }
                if (!expectedDocs.isEmpty()) {
                    long found = expectedDocs.stream().filter(topK::contains).count();
                    recalls.add((double) found / expectedDocs.size());
                    scored++;
                }
            }
        }

        double recallAtK = EvalMetrics.mean(recalls);
        String json = MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(Map.of(
                        "metric",
                        "rag_recall",
                        "fixtures_scored",
                        scored,
                        "recall_at_k",
                        recallAtK,
                        "forbidden_violations",
                        forbiddenViolations.size()));
        Path out = Paths.get(System.getProperty("user.dir"), "target/eval/baseline-rag-recall.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json);
        LOGGER.info("MCP baseline rag recall@k={} scored={} -> {}", recallAtK, scored, out);

        // Forbidden RAG docs leaking past the permission filter is a hard-fail security invariant.
        assertThat(forbiddenViolations)
                .as("forbidden (permission-negative) RAG docs must never be visible")
                .isEmpty();

        // AC3 (#783) recall floor — 0.85, ~10% below the live baseline recall@k 0.9574. Confirmed at
        // the production similarity floor (0.55, above) on the preload-repopulated corpus: identical to
        // the 0.0-threshold run, so the floor holds. Override with -Dmcp.eval.min-recall.
        double minRecall = Double.parseDouble(System.getProperty("mcp.eval.min-recall", "0.85"));
        assertThat(recallAtK).as("RAG recall@k floor").isGreaterThanOrEqualTo(minRecall);
    }

    /** Collapse retrieved chunks to distinct document_ids, preserving rank order. */
    private static List<String> distinctDocIds(List<Document> documents) {
        List<String> ordered = new ArrayList<>();
        for (Document document : documents) {
            Object raw = document.getMetadata().get(DOCUMENT_ID);
            if (raw instanceof String documentId && !documentId.isBlank() && !ordered.contains(documentId)) {
                ordered.add(documentId);
            }
        }
        return ordered;
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
