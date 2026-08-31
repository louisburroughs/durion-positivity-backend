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
import java.util.LinkedHashMap;
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
 *
 * <p>Both baselines report an explicit three-way {@link Participation} split — scored /
 * negative-only / skipped — and hard-fail on any skipped fixture (#1606 finding 3). Only fixtures
 * with a positive expectation feed hit@5, MRR and recall@k; the permission- and visibility-negative
 * sets have no positive expectation by design, so they are counted separately rather than folded
 * into the same silent drop as a malformed entry. Their forbidden assertions still run.
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
        List<String> skipped = new ArrayList<>();
        int total = 0;
        int negativeOnly = 0;

        for (Path file : suiteFiles("tool-selection")) {
            JsonNode root = MAPPER.readTree(file.toFile());
            for (JsonNode fx : root.get("fixtures")) {
                total++;
                Triage triage = triageToolSelection(fx);
                if (triage.participation() == Participation.SKIPPED) {
                    skipped.add(describeDefect(file, fx, triage.defect()));
                    continue;
                }

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

                // Forbidden assertions run for BOTH participations — a negative-only fixture is
                // fully evaluated here even though it contributes nothing to hit@5 / MRR.
                if (!forbidden.isEmpty() && !EvalMetrics.forbiddenAbsent(ranked, forbidden)) {
                    forbiddenViolations.add(Map.entry(id, true));
                }

                if (triage.participation() == Participation.SCORED) {
                    String primary = expectedTools.get(0);
                    hits.add(EvalMetrics.hitAtK(ranked, primary, K));
                    reciprocalRanks.add(EvalMetrics.reciprocalRank(ranked, primary));
                } else if (triage.participation() == Participation.NEGATIVE_ONLY) {
                    negativeOnly++;
                }
            }
        }

        int scored = hits.size();
        double hitAt5 = EvalMetrics.mean(hits);
        double mrr = EvalMetrics.mean(reciprocalRanks);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("metric", "tool_selection");
        baseline.put("fixtures_total", total);
        baseline.put("fixtures_scored", scored);
        baseline.put("fixtures_negative_only", negativeOnly);
        baseline.put("fixtures_skipped", skipped.size());
        baseline.put("skipped_fixtures", List.copyOf(skipped));
        baseline.put("hit_at_5", hitAt5);
        baseline.put("mrr", mrr);
        baseline.put("forbidden_violations", forbiddenViolations.size());
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(baseline);
        Path out = Paths.get(System.getProperty("user.dir"), "target/eval/baseline-tool-selection.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json);
        LOGGER.info(
                "MCP baseline tool-selection hit@5={} mrr={} scored={} negativeOnly={} skipped={} total={} -> {}",
                hitAt5,
                mrr,
                scored,
                negativeOnly,
                skipped.size(),
                total,
                out);

        // Corpus-integrity gate (#1606 finding 3) comes first: the metrics below only mean
        // something if every loaded fixture was accounted for. `scored` counts fixtures with a
        // positive expectation; `negativeOnly` counts fixtures that DECLARE themselves
        // permission-negative (expected.none = true with a non-empty forbidden_tool_ids), whose
        // forbidden assertions ran above. Anything else is a defect, never a silent drop.
        assertThat(skipped)
                .as("fixtures skipped for an unintended reason — a deliberate permission-negative must"
                        + " declare expected.none=true with a non-empty forbidden_tool_ids")
                .isEmpty();
        assertThat(scored + negativeOnly + skipped.size())
                .as("scored + negative-only + skipped must account for every loaded tool-selection fixture")
                .isEqualTo(total);

        // Security invariant is hard-fail even during baseline capture.
        assertThat(forbiddenViolations)
                .as("forbidden (permission-negative) tools must never be selected")
                .isEmpty();

        // AC4 (#783) quality floors — re-baselined 2026-07-29 (#1124) off the grown 39-doc corpus.
        // ~11% below the live-observed alpha baseline (hit@5 0.76, MRR 0.7222), matching eval_live.py.
        // Set with margin below observed. Override with -Dmcp.eval.min-hit5 / -Dmcp.eval.min-mrr.
        //
        // PROVISIONAL — these floors have never been validated against THIS harness (#1606
        // finding 2; determination in docs/gate-runs/2026-08-31-baseline-determination.md).
        // The 0.76 / 0.7222 observation above comes from scripts/eval_live.py, which by its own
        // header caveat scores the RAW ANN order; this IT scores the output of
        // ToolRegistryService.resolveCandidateTools — admin fast path, ToolScorer re-rank and
        // candidate limit applied. Same fixtures, different pipeline. The first live run of this
        // IT (2026-08-31) measured hit@5 0.60 / MRR 0.5677, i.e. below these floors, but that gap
        // is not by itself evidence of regression.
        // Do not re-baseline from that run either: V37 (2026-08-26) widened the permission gate
        // AFTER the 0.76 observation, and correcting it (#1606 finding 1, V40 per-method groups)
        // necessarily moves both metrics. Re-measure with this harness once V40 has landed, then
        // set these floors from that observation and cite the run here.
        double minHit5 = Double.parseDouble(System.getProperty("mcp.eval.min-hit5", "0.68"));
        double minMrr = Double.parseDouble(System.getProperty("mcp.eval.min-mrr", "0.64"));

        // While the floors are provisional (above), they fail identically whether the run is the
        // known 0.60 / 0.5677 or a fresh regression below it — so a further slide would be
        // invisible until the determination closes. These track the only values ever measured with
        // THIS harness (2026-08-31, commit 6a1abecac) and are asserted with a small tolerance, so a
        // drop below the known-bad point still fails loudly and distinguishably. Retire them, and
        // this block, when the floors are re-baselined post-V40.
        double lastObservedHit5 = Double.parseDouble(System.getProperty("mcp.eval.last-hit5", "0.60"));
        double lastObservedMrr = Double.parseDouble(System.getProperty("mcp.eval.last-mrr", "0.5677"));
        double tolerance = 0.02;
        assertThat(hitAt5)
                .as("tool-selection hit@5 must not fall below the last observed value (%s)", lastObservedHit5)
                .isGreaterThanOrEqualTo(lastObservedHit5 - tolerance);
        assertThat(mrr)
                .as("tool-selection MRR must not fall below the last observed value (%s)", lastObservedMrr)
                .isGreaterThanOrEqualTo(lastObservedMrr - tolerance);

        assertThat(hitAt5).as("tool-selection hit@5 floor").isGreaterThanOrEqualTo(minHit5);
        assertThat(mrr).as("tool-selection MRR floor").isGreaterThanOrEqualTo(minMrr);
    }

    @Test
    @DisplayName("capture RAG recall@k baseline against the live scope+permission-filtered retriever")
    void captureRagRecallBaseline() throws Exception {
        List<Double> recalls = new ArrayList<>();
        List<Map.Entry<String, Boolean>> forbiddenViolations = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int total = 0;
        int negativeOnly = 0;

        // Match production's cosine similarity floor so recall@k is neither over- nor under-reported:
        // score at the loosest value a doc must clear to enter the pipeline. SessionAgentManager builds
        // two dense retrievers — a primary at `mcp.rag.min-score` with topK 10, and a broad one at
        // `mcp.rag.tier2-min-score` over TIER2_RETRIEVAL_CANDIDATES. The retriever below uses a wide
        // candidate count (>= 50), so it mirrors the BROAD retriever, and the floor that belongs with it
        // is `mcp.rag.tier2-min-score` (0.40) — not the primary 0.45.
        //
        // Both floors are CALIBRATED PER EMBEDDING MODEL: the #1194 bge-m3 cutover moved production from
        // 0.6/0.55 (nomic-embed-text) to 0.45/0.40, because the two models put cosine similarity on
        // different scales. This constant hardcoded the old broad floor 0.55 and went stale at that
        // cutover, which made the test score stricter than production and under-report recall: measured
        // live on alpha 2026-08-18, the same 51 fixtures over the same corpus scored 0.6373 at 0.55
        // versus 0.8922 at 0.45. That looked like a retrieval regression and was purely a measurement
        // artifact.
        //
        // Keep this in step with `mcp.rag.tier2-min-score` in application.yml whenever the embedding
        // model changes. Override with -Dmcp.eval.rag-min-score.
        double ragMinScore = Double.parseDouble(System.getProperty("mcp.eval.rag-min-score", "0.40"));

        for (Path file : suiteFiles("rag-retrieval")) {
            JsonNode root = MAPPER.readTree(file.toFile());
            for (JsonNode fx : root.get("fixtures")) {
                total++;
                Triage triage = triageRagRetrieval(fx);
                if (triage.participation() == Participation.SKIPPED) {
                    skipped.add(describeDefect(file, fx, triage.defect()));
                    continue;
                }

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

                // Forbidden assertions run for BOTH participations — a visibility-negative fixture
                // is fully evaluated here even though it contributes nothing to recall@k.
                if (!forbidden.isEmpty() && topK.stream().anyMatch(forbidden::contains)) {
                    forbiddenViolations.add(Map.entry(id, true));
                }

                if (triage.participation() == Participation.SCORED) {
                    long found = expectedDocs.stream().filter(topK::contains).count();
                    recalls.add((double) found / expectedDocs.size());
                } else if (triage.participation() == Participation.NEGATIVE_ONLY) {
                    negativeOnly++;
                }
            }
        }

        int scored = recalls.size();
        double recallAtK = EvalMetrics.mean(recalls);
        Map<String, Object> baseline = new LinkedHashMap<>();
        baseline.put("metric", "rag_recall");
        baseline.put("fixtures_total", total);
        baseline.put("fixtures_scored", scored);
        baseline.put("fixtures_negative_only", negativeOnly);
        baseline.put("fixtures_skipped", skipped.size());
        baseline.put("skipped_fixtures", List.copyOf(skipped));
        baseline.put("recall_at_k", recallAtK);
        baseline.put("forbidden_violations", forbiddenViolations.size());
        String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(baseline);
        Path out = Paths.get(System.getProperty("user.dir"), "target/eval/baseline-rag-recall.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json);
        LOGGER.info(
                "MCP baseline rag recall@k={} scored={} negativeOnly={} skipped={} total={} -> {}",
                recallAtK,
                scored,
                negativeOnly,
                skipped.size(),
                total,
                out);

        // Corpus-integrity gate (#1606 finding 3), same shape as the tool-selection half. The
        // rag-retrieval schema has no `expected.none` flag, so a visibility-negative declares
        // itself by pairing an empty doc_ids with a non-empty forbidden_doc_ids — a fixture that
        // expects nothing AND forbids nothing asserts nothing and is a defect, not a negative.
        assertThat(skipped)
                .as("fixtures skipped for an unintended reason — a deliberate visibility-negative must"
                        + " pair an empty doc_ids with a non-empty forbidden_doc_ids")
                .isEmpty();
        assertThat(scored + negativeOnly + skipped.size())
                .as("scored + negative-only + skipped must account for every loaded rag-retrieval fixture")
                .isEqualTo(total);

        // Forbidden RAG docs leaking past the permission filter is a hard-fail security invariant.
        assertThat(forbiddenViolations)
                .as("forbidden (permission-negative) RAG docs must never be visible")
                .isEmpty();

        // AC3 (#783) recall floor — re-baselined 2026-07-29 (#1124) to 0.76, ~11% below the 39-doc
        // corpus baseline recall@k 0.8571 (was 0.85 off the old 17-doc 0.9574 baseline; the corpus
        // doubling in #1163 dropped observed recall to within ~0.001 of that old floor). Confirmed at
        // the production similarity floor (0.55, above) on the preload-repopulated corpus. Deterministic
        // across three re-embed cycles. Override with -Dmcp.eval.min-recall.
        double minRecall = Double.parseDouble(System.getProperty("mcp.eval.min-recall", "0.76"));
        assertThat(recallAtK).as("RAG recall@k floor").isGreaterThanOrEqualTo(minRecall);
    }

    /**
     * How a fixture took part in the metric. Every loaded fixture lands in exactly one arm, and the
     * three counts are asserted to sum to the corpus size, so the scorer can no longer drop a
     * fixture silently (#1606 finding 3).
     */
    private enum Participation {
        /** Carries a positive expectation; contributed to hit@5 / MRR (or recall@k). */
        SCORED,
        /**
         * Deliberately has no positive expectation — the permission/visibility-negative set. Its
         * forbidden assertions are still evaluated; only the ranking metrics skip it.
         */
        NEGATIVE_ONLY,
        /** Fell through for an unintended reason (malformed / unclassifiable). Always a failure. */
        SKIPPED
    }

    /** Triage outcome; {@code defect} is non-null only for {@link Participation#SKIPPED}. */
    private record Triage(Participation participation, String defect) {

        static Triage scored() {
            return new Triage(Participation.SCORED, null);
        }

        static Triage negativeOnly() {
            return new Triage(Participation.NEGATIVE_ONLY, null);
        }

        static Triage skipped(String defect) {
            return new Triage(Participation.SKIPPED, defect);
        }
    }

    /**
     * Classify one tool-selection fixture BEFORE the selector runs, so a malformed entry is named
     * rather than exploding mid-loop.
     *
     * <p>NEGATIVE_ONLY is reached only through an explicit self-declaration — {@code
     * expected.none = true} together with a non-empty {@code forbidden_tool_ids} to actually assert
     * against. It is deliberately not a fallback for "empty {@code tool_ids}": a fixture whose
     * {@code tool_ids} is empty because it is broken must land in SKIPPED, which is the entire point
     * of the split.
     */
    private static Triage triageToolSelection(JsonNode fx) {
        String shape = commonDefect(fx, "utterance");
        if (shape != null) {
            return Triage.skipped(shape);
        }
        JsonNode expected = fx.get("expected");
        JsonNode toolIds = expected.get("tool_ids");
        if (toolIds == null || !toolIds.isArray()) {
            return Triage.skipped("expected.tool_ids missing or not an array");
        }
        if (!toolIds.isEmpty()) {
            return Triage.scored();
        }
        JsonNode none = expected.get("none");
        if (none == null || !none.isBoolean() || !none.booleanValue()) {
            return Triage.skipped("empty expected.tool_ids without expected.none=true");
        }
        JsonNode forbidden = expected.get("forbidden_tool_ids");
        if (forbidden == null || !forbidden.isArray() || forbidden.isEmpty()) {
            return Triage.skipped("expected.none=true but no forbidden_tool_ids to assert");
        }
        return Triage.negativeOnly();
    }

    /**
     * Classify one rag-retrieval fixture. The rag-retrieval schema has no {@code expected.none}
     * flag, so the explicit negative declaration here is an empty {@code doc_ids} paired with a
     * non-empty {@code forbidden_doc_ids} (the {@code visibility-negative} tag is convention, not
     * the gate). A fixture that expects nothing and forbids nothing asserts nothing, so it is a
     * defect rather than a negative.
     */
    private static Triage triageRagRetrieval(JsonNode fx) {
        String shape = commonDefect(fx, "query");
        if (shape != null) {
            return Triage.skipped(shape);
        }
        JsonNode expected = fx.get("expected");
        JsonNode docIds = expected.get("doc_ids");
        if (docIds == null || !docIds.isArray()) {
            return Triage.skipped("expected.doc_ids missing or not an array");
        }
        if (!docIds.isEmpty()) {
            return Triage.scored();
        }
        JsonNode forbidden = expected.get("forbidden_doc_ids");
        if (forbidden == null || !forbidden.isArray() || forbidden.isEmpty()) {
            return Triage.skipped("empty expected.doc_ids without any forbidden_doc_ids to assert");
        }
        return Triage.negativeOnly();
    }

    /** Structural checks shared by both suites; null when the fixture is well-formed. */
    private static String commonDefect(JsonNode fx, String promptField) {
        if (fx == null || !fx.isObject()) {
            return "fixture entry is not a JSON object";
        }
        if (fx.path("fixture_id").asText("").isBlank()) {
            return "fixture_id missing or blank";
        }
        if (fx.path(promptField).asText("").isBlank()) {
            return promptField + " missing or blank";
        }
        JsonNode actor = fx.get("actor");
        if (actor == null || !actor.isObject()) {
            return "actor block missing";
        }
        if (actor.path("role").asText("").isBlank()) {
            return "actor.role missing or blank";
        }
        JsonNode expected = fx.get("expected");
        if (expected == null || !expected.isObject()) {
            return "expected block missing";
        }
        return null;
    }

    /** Render a skipped fixture for the failure message: which file, which id, and why. */
    private static String describeDefect(Path file, JsonNode fx, String defect) {
        String id = fx == null ? "" : fx.path("fixture_id").asText("");
        return "%s[%s]: %s".formatted(file.getFileName(), id.isBlank() ? "<no fixture_id>" : id, defect);
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
