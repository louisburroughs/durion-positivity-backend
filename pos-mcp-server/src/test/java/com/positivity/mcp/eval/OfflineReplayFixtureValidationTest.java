package com.positivity.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.orchestration.tools.AccountingFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.AdminFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.CatalogFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.CustomerFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.DateWindowFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.EventsFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.HrFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.InventoryFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.InvoiceFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.LocationFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.OrderFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.PricingFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.ReportingFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.ShopManagerFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.TaxFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.VehicleFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.WorkorderFacadeTool;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;

/**
 * Structural gate for the offline replay fixture suite (#1682). CI-safe: no
 * model backend, no
 * database. Validates the fixture file's internal consistency and its
 * cross-link to the versioned
 * analytics-gate question set ({@code analytics-gate/QUESTIONS.json}),
 * mirroring
 * {@link AnalyticsGateQuestionsTest}'s bijection style so a fixture drift shows
 * up as a build
 * failure rather than a silently stale replay case.
 */
class OfflineReplayFixtureValidationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path EVAL_ROOT = Paths.get(System.getProperty("user.dir"), "src/test/resources/eval");
    private static final Path REPLAY_FIXTURES = EVAL_ROOT.resolve("offline-replay/analytics-gate-replay.json");
    private static final Path QUESTIONS = EVAL_ROOT.resolve("analytics-gate/QUESTIONS.json");
    private static final Path FACADE_CONTRACT =
            Paths.get(System.getProperty("user.dir"), "src/test/resources/facade-contract.yaml");

    private static final Set<String> ALLOWED_OUTCOMES =
            Set.of("answered-correctly", "asked-appropriately", "declined-appropriately", "failed");

    /**
     * Discovered (OpenAPI, {@code {domain}_{operationId}}) tool names used by
     * fixtures that are not
     * served by a curated facade — resolved once, from the owning controllers,
     * rather than guessed:
     * {@code WorkorderAnalyticsController.getReopenedWorkorderAnalytics},
     * {@code AccountingAnalyticsController.getPaymentLagCohorts}, and
     * {@code VendorBillController.listVendorBills}.
     */
    private static final Set<String> KNOWN_DISCOVERED_TOOL_NAMES = Set.of(
            "workorder_getReopenedWorkorderAnalytics", "accounting_getPaymentLagCohorts", "accounting_listVendorBills");

    private static final List<Class<?>> FACADE_TOOL_CLASSES = List.of(
            AccountingFacadeTool.class,
            AdminFacadeTool.class,
            CatalogFacadeTool.class,
            CustomerFacadeTool.class,
            DateWindowFacadeTool.class,
            EventsFacadeTool.class,
            HrFacadeTool.class,
            InventoryFacadeTool.class,
            InvoiceFacadeTool.class,
            LocationFacadeTool.class,
            OrderFacadeTool.class,
            PricingFacadeTool.class,
            ReportingFacadeTool.class,
            ShopManagerFacadeTool.class,
            TaxFacadeTool.class,
            VehicleFacadeTool.class,
            WorkorderFacadeTool.class);

    @Test
    @DisplayName("suite header is well-formed")
    void suiteHeaderIsWellFormed() throws IOException {
        JsonNode root = fixtures();
        assertThat(root.path("schema_version").asInt()).isEqualTo(1);
        assertThat(root.path("suite").asText()).isEqualTo("offline-replay");
        assertThat(root.get("fixtures")).isNotNull();
    }

    @Test
    @DisplayName("every fixture cross-links to an in_chat_path_gate QUESTIONS.json entry, and the set is exact")
    void fixturesCoverExactlyTheChatPathGateSet() throws IOException {
        Set<String> gatedQuestionIds = new LinkedHashSet<>();
        for (JsonNode question : questions().get("questions")) {
            if (question.path("in_chat_path_gate").asBoolean()) {
                gatedQuestionIds.add(question.path("fixture_id").asText());
            }
        }

        Set<String> fixtureIds = new LinkedHashSet<>();
        Set<String> linkedIds = new LinkedHashSet<>();
        for (JsonNode fixture : fixtures().get("fixtures")) {
            String id = fixture.path("fixture_id").asText();
            assertThat(id).as("fixture_id required").matches("q\\d{2}");
            assertThat(fixtureIds.add(id)).as("%s: duplicate fixture_id", id).isTrue();

            String link = fixture.path("analytics_gate_fixture_id").asText();
            assertThat(gatedQuestionIds)
                    .as("%s: analytics_gate_fixture_id '%s' must name an in_chat_path_gate question", id, link)
                    .contains(link);
            linkedIds.add(link);
        }
        assertThat(linkedIds)
                .as("offline-replay fixtures must cover exactly the twelve in_chat_path_gate questions")
                .isEqualTo(gatedQuestionIds);
    }

    @Test
    @DisplayName("every offered/expected/canned tool name is real")
    void everyToolNameIsReal() throws IOException {
        Set<String> realToolNames = new HashSet<>(facadeToolMethodNames());
        realToolNames.addAll(KNOWN_DISCOVERED_TOOL_NAMES);
        assertThat(realToolNames).isNotEmpty();

        for (JsonNode fixture : fixtures().get("fixtures")) {
            String id = fixture.path("fixture_id").asText();
            Set<String> offeredNames = new HashSet<>();
            for (JsonNode tool : fixture.get("offered_tools")) {
                String name = tool.path("name").asText();
                assertThat(realToolNames)
                        .as("%s: offered tool '%s' is not a real tool name", id, name)
                        .contains(name);
                assertThat(offeredNames.add(name))
                        .as("%s: offered tool '%s' duplicated", id, name)
                        .isTrue();
            }
            assertThat(offeredNames)
                    .as("%s: offered_tools must not be empty", id)
                    .isNotEmpty();

            for (JsonNode canned : fixture.path("tool_responses")) {
                String toolName = canned.path("tool_name").asText();
                assertThat(offeredNames)
                        .as("%s: canned response names '%s', which is not among offered_tools", id, toolName)
                        .contains(toolName);
            }

            for (JsonNode expectedCall : fixture.path("expected").path("tool_call_sequence")) {
                String toolName = expectedCall.asText();
                assertThat(offeredNames)
                        .as(
                                "%s: expected.tool_call_sequence names '%s', which is not among offered_tools",
                                id, toolName)
                        .contains(toolName);
            }
        }
    }

    @Test
    @DisplayName("every fixture declares a supported outcome and consistent expectations")
    void expectationsAreWellFormed() throws IOException {
        for (JsonNode fixture : fixtures().get("fixtures")) {
            String id = fixture.path("fixture_id").asText();
            JsonNode expected = fixture.get("expected");
            assertThat(expected).as("%s: expected required", id).isNotNull();
            assertThat(ALLOWED_OUTCOMES)
                    .as("%s: expected.outcome must be one of %s", id, ALLOWED_OUTCOMES)
                    .contains(expected.path("outcome").asText());
            assertThat(expected.get("tool_call_sequence"))
                    .as("%s: expected.tool_call_sequence required (may be empty)", id)
                    .isNotNull();

            for (JsonNode number : expected.path("numbers")) {
                assertThat(number.path("label").asText())
                        .as("%s: numbers[].label required", id)
                        .isNotBlank();
                assertThat(number.path("tolerance_pct").asDouble())
                        .as("%s: numbers[].tolerance_pct must be >= 0", id)
                        .isGreaterThanOrEqualTo(0);
            }

            assertUniqueStrings(id, "id_set", expected.path("id_set"));
            assertUniqueStrings(id, "id_order", expected.path("id_order"));
        }
    }

    @Test
    @DisplayName("every utterance and window match its analytics-gate QUESTIONS.json counterpart")
    void utterancesMatchQuestionsJson() throws IOException {
        java.util.Map<String, String> utteranceByFixtureId = new java.util.HashMap<>();
        for (JsonNode question : questions().get("questions")) {
            utteranceByFixtureId.put(
                    question.path("fixture_id").asText(),
                    question.path("utterance").asText());
        }
        for (JsonNode fixture : fixtures().get("fixtures")) {
            String id = fixture.path("fixture_id").asText();
            String link = fixture.path("analytics_gate_fixture_id").asText();
            assertThat(fixture.path("utterance").asText())
                    .as("%s: utterance must match QUESTIONS.json %s verbatim", id, link)
                    .isEqualTo(utteranceByFixtureId.get(link));
        }
    }

    private static void assertUniqueStrings(String fixtureId, String field, JsonNode array) {
        Set<String> seen = new HashSet<>();
        for (JsonNode entry : array) {
            assertThat(seen.add(entry.asText()))
                    .as("%s: %s has a duplicate entry '%s'", fixtureId, field, entry.asText())
                    .isTrue();
        }
    }

    private static JsonNode fixtures() throws IOException {
        return MAPPER.readTree(REPLAY_FIXTURES.toFile());
    }

    private static JsonNode questions() throws IOException {
        return MAPPER.readTree(QUESTIONS.toFile());
    }

    /**
     * Real facade {@code @Tool} method names, derived from
     * {@code facade-contract.yaml} keys.
     */
    private static Set<String> facadeToolMethodNames() throws IOException {
        Set<String> contractKeys = new HashSet<>();
        for (String line : Files.readAllLines(FACADE_CONTRACT)) {
            int colon = line.indexOf(':');
            if (colon > 0 && !line.startsWith(" ") && !line.startsWith("#") && line.contains(".")) {
                contractKeys.add(line.substring(0, colon).trim());
            }
        }
        Set<String> methodNames = new HashSet<>();
        for (Class<?> facadeClass : FACADE_TOOL_CLASSES) {
            for (Method method : facadeClass.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)
                        && contractKeys.contains(facadeClass.getSimpleName() + "." + method.getName())) {
                    methodNames.add(method.getName());
                }
            }
        }
        return methodNames;
    }
}
