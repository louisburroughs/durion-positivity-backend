package com.positivity.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Gate 0 evaluation harness — structural half.
 *
 * <p>Validates every eval fixture file against the structural rules in
 * {@code docs/phase0-fixtures-and-telemetry.md} without requiring a running Ollama / Spring
 * context. This is the CI-safe portion of the harness: it makes malformed or drifting fixtures
 * fail loudly and actionably.
 *
 * <p>The metric-computation half (hit@5 / MRR / recall@k, write-safety assertions) drives the
 * live tool-selection and retrieval paths and therefore requires a running model backend; it is
 * tracked separately and is runtime-gated, not part of this structural test.
 */
class EvalFixtureValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path EVAL_ROOT = Paths.get(System.getProperty("user.dir"), "src/test/resources/eval");

    private static final Set<String> KNOWN_ROLES = Set.of(
            "ROLE_SYSTEM_ADMINISTRATOR",
            "ROLE_ADMIN",
            "ROLE_LOCATION_MANAGER",
            "ROLE_ACCOUNT_MANAGER",
            "ROLE_ACCOUNTING_ASSOCIATE",
            "ROLE_SERVICE_ADVISOR",
            "ROLE_DISPATCHER",
            "ROLE_TECHNICIAN",
            "ROLE_USER");
    private static final Set<String> WORKFLOW_STATES =
            Set.of("IDLE", "CREATING_PO", "RECEIVING_ASN", "INVENTORY_RECON", "PROCESSING_RETURN");
    private static final Set<String> INTENT_TYPES = Set.of("QUERY", "ACTION", "UNKNOWN");
    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");

    // Gate 0 completeness minimums (docs/implementation_phase_gates.md).
    private static final int MIN_TOOL_SELECTION = 100;
    private static final int MIN_RAG_RETRIEVAL = 50;
    private static final int MIN_WRITE_SAFETY = 30;

    @Test
    @DisplayName("tool-selection fixtures are structurally valid")
    void toolSelectionFixturesValid() throws IOException {
        for (Path file : suiteFiles("tool-selection")) {
            JsonNode root = parseSuite(file);
            Set<String> ids = new HashSet<>();
            for (JsonNode fx : root.get("fixtures")) {
                String id = requireId(fx, file, ids);
                requireText(fx, "utterance", file, id);
                validateActor(fx.get("actor"), file, id, true);
                JsonNode expected = required(fx, "expected", file, id);
                assertThat(expected.has("tool_ids"))
                        .as("%s[%s]: expected.tool_ids required", file, id)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("rag-retrieval fixtures are structurally valid")
    void ragRetrievalFixturesValid() throws IOException {
        for (Path file : suiteFiles("rag-retrieval")) {
            JsonNode root = parseSuite(file);
            Set<String> ids = new HashSet<>();
            for (JsonNode fx : root.get("fixtures")) {
                String id = requireId(fx, file, ids);
                requireText(fx, "query", file, id);
                validateActor(fx.get("actor"), file, id, false);
                JsonNode expected = required(fx, "expected", file, id);
                assertThat(expected.has("doc_ids"))
                        .as("%s[%s]: expected.doc_ids required", file, id)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("write-safety fixtures are structurally valid")
    void writeSafetyFixturesValid() throws IOException {
        for (Path file : suiteFiles("write-safety")) {
            JsonNode root = parseSuite(file);
            Set<String> ids = new HashSet<>();
            for (JsonNode fx : root.get("fixtures")) {
                String id = requireId(fx, file, ids);
                requireText(fx, "utterance", file, id);
                validateActor(fx.get("actor"), file, id, false);
                JsonNode expected = required(fx, "expected", file, id);
                assertThat(INTENT_TYPES)
                        .as("%s[%s]: expected.intent_type must be a known NltiIntentType", file, id)
                        .contains(expected.path("intent_type").asText());
                if (expected.has("risk_level")) {
                    assertThat(RISK_LEVELS)
                            .as("%s[%s]: expected.risk_level must be a known NltiRiskLevel", file, id)
                            .contains(expected.get("risk_level").asText());
                }
                assertThat(expected.path("tool_id").asText())
                        .as("%s[%s]: expected.tool_id required", file, id)
                        .isNotBlank();
                assertThat(required(fx, "assertions", file, id).isObject())
                        .as("%s[%s]: assertions object required", file, id)
                        .isTrue();
            }
        }
    }

    @Test
    @Disabled("Gate 0 exit criterion: author fixtures to >=100/50/30. Currently seed-only; "
            + "this test must be enabled and pass before Gate 0 can be signed Pass.")
    @DisplayName("Gate 0 exit: fixture suites meet minimum counts")
    void minimumFixtureCountsMet() throws IOException {
        assertThat(countFixtures("tool-selection")).isGreaterThanOrEqualTo(MIN_TOOL_SELECTION);
        assertThat(countFixtures("rag-retrieval")).isGreaterThanOrEqualTo(MIN_RAG_RETRIEVAL);
        assertThat(countFixtures("write-safety")).isGreaterThanOrEqualTo(MIN_WRITE_SAFETY);
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    private static List<Path> suiteFiles(String suite) throws IOException {
        Path dir = EVAL_ROOT.resolve(suite);
        assertThat(Files.isDirectory(dir))
                .as("eval suite dir must exist: %s", dir)
                .isTrue();
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files =
                    s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
            assertThat(files)
                    .as("eval suite %s must contain at least one fixture file", suite)
                    .isNotEmpty();
            return files;
        }
    }

    private static int countFixtures(String suite) throws IOException {
        int n = 0;
        for (Path file : suiteFiles(suite)) {
            n += parseSuite(file).get("fixtures").size();
        }
        return n;
    }

    private static JsonNode parseSuite(Path file) throws IOException {
        JsonNode root = MAPPER.readTree(file.toFile());
        assertThat(root.path("schema_version").asInt())
                .as("%s: schema_version must be 1", file)
                .isEqualTo(1);
        assertThat(root.path("fixtures").isArray())
                .as("%s: fixtures[] required", file)
                .isTrue();
        return root;
    }

    private static String requireId(JsonNode fx, Path file, Set<String> seen) {
        String id = fx.path("fixture_id").asText();
        assertThat(id).as("%s: fixture_id required and kebab-case", file).matches("[a-z0-9-]+");
        assertThat(seen.add(id)).as("%s: duplicate fixture_id '%s'", file, id).isTrue();
        return id;
    }

    private static void validateActor(JsonNode actor, Path file, String id, boolean allowWorkflow) {
        assertThat(actor).as("%s[%s]: actor required", file, id).isNotNull();
        assertThat(KNOWN_ROLES)
                .as("%s[%s]: actor.role must be a known role", file, id)
                .contains(actor.path("role").asText());
        assertThat(actor.path("permission_codes").isArray())
                .as("%s[%s]: actor.permission_codes[] required", file, id)
                .isTrue();
        if (actor.has("workflow_state")) {
            assertThat(allowWorkflow)
                    .as("%s[%s]: workflow_state only valid on tool-selection fixtures", file, id)
                    .isTrue();
            assertThat(WORKFLOW_STATES)
                    .as("%s[%s]: workflow_state must be known", file, id)
                    .contains(actor.get("workflow_state").asText());
        }
    }

    private static JsonNode required(JsonNode fx, String field, Path file, String id) {
        JsonNode n = fx.get(field);
        assertThat(n).as("%s[%s]: '%s' required", file, id, field).isNotNull();
        return n;
    }

    private static void requireText(JsonNode fx, String field, Path file, String id) {
        assertThat(fx.path(field).asText())
                .as("%s[%s]: '%s' required non-empty", file, id, field)
                .isNotBlank();
    }
}
