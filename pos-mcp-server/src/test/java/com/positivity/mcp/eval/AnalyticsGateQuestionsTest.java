package com.positivity.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.orchestration.tools.AccountingFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.AdminFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.CatalogFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.CustomerFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.DateWindowFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.EventsFacadeTool;
import com.positivity.mcp.internal.orchestration.tools.GlossaryFacadeTool;
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
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.yaml.snakeyaml.Yaml;

/**
 * Structural gate for the versioned analytics-gate question set (#1671).
 *
 * <p>{@code eval/analytics-gate/QUESTIONS.json} is the single definition of the text the chat-path
 * gate asks. Before it existed the questions lived only in an ad-hoc list on the operator's machine,
 * so a gate score could move without a code change, a reworded question was indistinguishable from a
 * regression, and nothing tied an asked question to the {@code ## QN} section of {@code EXPECTED.md}
 * that scores it — which is how gate q09 came to be asked with a calendar-year window against a
 * ground truth measuring twelve calendar months, and scored UNSCORABLE.
 *
 * <p>These assertions are what makes the file authoritative rather than merely present: the question
 * set and the ground truth must stay in bijection, every question must resolve to a window, and the
 * cross-link to the tool-selection corpus must name a fixture that exists. They need no database and
 * no model backend, so they run in ordinary CI alongside {@link EvalFixtureValidationTest}.
 */
class AnalyticsGateQuestionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path GATE_ROOT =
            Paths.get(System.getProperty("user.dir"), "src/test/resources/eval/analytics-gate");
    private static final Path QUESTIONS = GATE_ROOT.resolve("QUESTIONS.json");
    private static final Path EXPECTED = GATE_ROOT.resolve("ground-truth/EXPECTED.md");
    private static final Path TOOL_SELECTION =
            Paths.get(System.getProperty("user.dir"), "src/test/resources/eval/tool-selection");
    private static final Path TOOL_SELECTION_PENDING =
            Paths.get(System.getProperty("user.dir"), "src/test/resources/eval/tool-selection-pending");
    private static final Path FACADE_CONTRACT =
            Paths.get(System.getProperty("user.dir"), "src/test/resources/facade-contract.yaml");

    /** Plan §6 is a twenty-question matrix; the file carries all twenty, gated or not. */
    private static final int PLAN_QUESTION_COUNT = 25;

    private static final Set<String> WINDOW_SHAPES = Set.of("calendar", "rolling", "point-in-time", "mixed");

    /**
     * Window shapes that carry an actual range (as opposed to {@code point-in-time}, which is a
     * single as-of instant with nothing to pass in more than one call, or {@code mixed}, which no
     * {@code expected_plan} question uses today). Only these shapes are checked by {@link
     * #expectedPlanToolsCanReceiveTheWindowInOneCall()}.
     */
    private static final Set<String> WINDOW_SHAPES_WITH_A_RANGE = Set.of("calendar", "rolling");

    private static final Pattern TEMPLATE_PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9]+)}");

    /** Every {@code *FacadeTool} class under test, mirroring {@code FacadeContractManifestTest}. */
    private static final List<Class<?>> FACADE_TOOL_CLASSES = List.of(
            AccountingFacadeTool.class,
            AdminFacadeTool.class,
            CatalogFacadeTool.class,
            CustomerFacadeTool.class,
            DateWindowFacadeTool.class,
            GlossaryFacadeTool.class,
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
    @DisplayName("every plan §6 question is versioned exactly once, in order")
    void questionsAreCompleteAndOrdered() throws IOException {
        JsonNode root = questions();
        List<String> ids = new ArrayList<>();
        for (JsonNode q : root.get("questions")) {
            ids.add(q.path("fixture_id").asText());
        }
        assertThat(ids).hasSize(PLAN_QUESTION_COUNT).doesNotHaveDuplicates().isSorted();
        for (int i = 0; i < ids.size(); i++) {
            assertThat(ids.get(i))
                    .as("questions[%d]: ids are the zero-padded qNN of plan §6", i)
                    .isEqualTo("q%02d".formatted(i + 1));
        }
    }

    @Test
    @DisplayName("each question pairs with its EXPECTED.md section and its ground-truth SQL")
    void questionsPairWithGroundTruth() throws IOException {
        Set<String> sections = expectedSections();
        Set<String> claimed = new LinkedHashSet<>();
        for (JsonNode q : questions().get("questions")) {
            String id = q.path("fixture_id").asText();
            String section = q.path("expected_section").asText();
            assertThat(section)
                    .as("%s: expected_section must be its EXPECTED.md heading", id)
                    .isEqualTo("Q" + Integer.parseInt(id.substring(1)));
            assertThat(sections)
                    .as("%s: EXPECTED.md has no '## %s' section", id, section)
                    .contains(section);
            claimed.add(section);

            Path sql = GATE_ROOT.resolve(q.path("ground_truth_sql").asText());
            assertThat(Files.isRegularFile(sql))
                    .as("%s: ground_truth_sql must exist (%s)", id, sql)
                    .isTrue();
        }
        // The bijection is the point: a ground truth nobody asks a question for is as broken as a
        // question with no ground truth, and either one silently changes what "the gate" means.
        assertThat(sections)
                .as("EXPECTED.md sections with no versioned question")
                .isEqualTo(claimed);
    }

    @Test
    @DisplayName("every question carries a resolvable window")
    void everyQuestionResolvesAWindow() throws IOException {
        for (JsonNode q : questions().get("questions")) {
            String id = q.path("fixture_id").asText();
            JsonNode window = q.get("window");
            assertThat(window).as("%s: window required", id).isNotNull();
            assertThat(WINDOW_SHAPES)
                    .as("%s: window.shape must be one of %s", id, WINDOW_SHAPES)
                    .contains(window.path("shape").asText());
            assertThat(window.path("resolved_range").asText())
                    .as("%s: window.resolved_range required", id)
                    .isNotBlank();
            assertThat(window.path("stated_in_question").isBoolean())
                    .as("%s: window.stated_in_question required", id)
                    .isTrue();
            // A question whose text does not pin its own window is exactly the q09 defect. It is not
            // banned outright — q17 still has it — but it may never be silent again: the divergence
            // has to be written down where a reviewer diffing this file will see it.
            if (!window.path("stated_in_question").asBoolean()) {
                assertThat(window.path("notes").asText())
                        .as("%s: unstated window must be explained in window.notes", id)
                        .isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("the chat-path gate set is declared, consistent, and every exclusion is justified")
    void chatPathGateSetIsConsistent() throws IOException {
        JsonNode root = questions();
        JsonNode gate = root.get("chat_path_gate");
        assertThat(gate).as("chat_path_gate block required").isNotNull();

        Set<String> declared = new LinkedHashSet<>();
        gate.path("fixture_ids").forEach(n -> declared.add(n.asText()));
        assertThat(gate.path("rationale").asText())
                .as("chat_path_gate.rationale required")
                .isNotBlank();

        Set<String> flagged = new LinkedHashSet<>();
        for (JsonNode q : root.get("questions")) {
            String id = q.path("fixture_id").asText();
            assertThat(q.path("in_chat_path_gate").isBoolean())
                    .as("%s: in_chat_path_gate required", id)
                    .isTrue();
            if (q.path("in_chat_path_gate").asBoolean()) {
                flagged.add(id);
                assertThat(q.path("excluded_reason").isNull())
                        .as("%s: a gated question carries no excluded_reason", id)
                        .isTrue();
            } else {
                assertThat(q.path("excluded_reason").asText())
                        .as("%s: an excluded question must say why it is excluded", id)
                        .isNotBlank();
            }
            assertThat(q.path("utterance").asText())
                    .as("%s: utterance required", id)
                    .isNotBlank();
        }
        assertThat(flagged)
                .as("chat_path_gate.fixture_ids must match the in_chat_path_gate flags")
                .isEqualTo(declared);
        assertThat(flagged)
                .as("chat_path_gate.size must match the set it names")
                .hasSize(gate.path("size").asInt());
    }

    @Test
    @DisplayName("the tool-selection cross-link names a fixture that exists")
    void toolSelectionCrossLinkResolves() throws IOException {
        Set<String> selectionIds = new HashSet<>();
        for (Path dir : List.of(TOOL_SELECTION, TOOL_SELECTION_PENDING)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path file :
                        files.filter(p -> p.toString().endsWith(".json")).toList()) {
                    for (JsonNode fx : MAPPER.readTree(file.toFile()).path("fixtures")) {
                        selectionIds.add(fx.path("fixture_id").asText());
                    }
                }
            }
        }
        for (JsonNode q : questions().get("questions")) {
            String id = q.path("fixture_id").asText();
            String link = q.path("tool_selection_fixture_id").asText();
            // The two corpora share the qNN numbering and score different things; that overlap already
            // caused one misreading during #1661. Pinning the link keeps the pairing explicit instead
            // of inferred from a number that means something else on the other side.
            assertThat(selectionIds)
                    .as("%s: tool_selection_fixture_id '%s' matches no fixture", id, link)
                    .contains(link);
        }
    }

    /**
     * #1676: a composition question (q05's "aged receivables, then one searchWorkorders call per
     * past-due customer" being the motivating case) may carry an optional {@code expected_plan}
     * naming the minimum tool calls the composition needs. This pins the field to reality on both
     * sides: every key in {@code min_tool_calls} must be a real facade {@code @Tool} method name
     * (derived from {@code facade-contract.yaml}, never restated as a literal set here, so a facade
     * rename shows up as a test failure rather than a silently stale fixture) and every minimum must
     * be a positive count. A question with no composition to name simply omits the field — this test
     * does not require one.
     */
    @Test
    @DisplayName("expected_plan, when present, names only real facade tools with positive minimums")
    void expectedPlanNamesRealFacadeToolsWithPositiveMinimums() throws IOException {
        Set<String> facadeToolNames = facadeToolMethodNames();
        assertThat(facadeToolNames).as("facade-contract.yaml must not be empty").isNotEmpty();

        for (JsonNode q : questions().get("questions")) {
            String id = q.path("fixture_id").asText();
            JsonNode plan = q.get("expected_plan");
            if (plan == null || plan.isNull()) {
                continue;
            }
            JsonNode minCalls = plan.get("min_tool_calls");
            assertThat(minCalls)
                    .as("%s: expected_plan.min_tool_calls required when expected_plan is present", id)
                    .isNotNull();
            assertThat(minCalls.isObject())
                    .as("%s: expected_plan.min_tool_calls must be an object", id)
                    .isTrue();
            assertThat(minCalls.size())
                    .as("%s: expected_plan.min_tool_calls must name at least one tool", id)
                    .isPositive();

            Iterator<Map.Entry<String, JsonNode>> fields = minCalls.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                assertThat(facadeToolNames)
                        .as(
                                "%s: expected_plan.min_tool_calls names '%s', which is not a real facade "
                                        + "@Tool method (see facade-contract.yaml)",
                                id, entry.getKey())
                        .contains(entry.getKey());
                assertThat(entry.getValue().isInt())
                        .as("%s: expected_plan.min_tool_calls.%s must be an integer", id, entry.getKey())
                        .isTrue();
                assertThat(entry.getValue().asInt())
                        .as("%s: expected_plan.min_tool_calls.%s must be positive", id, entry.getKey())
                        .isPositive();
            }

            assertThat(plan.path("per").asText())
                    .as("%s: expected_plan.per must explain the per-unit composition", id)
                    .isNotBlank();
        }
    }

    /**
     * #1677 F4: {@link #expectedPlanNamesRealFacadeToolsWithPositiveMinimums} only checks that a
     * {@code min_tool_calls} name is a real facade {@code @Tool} method with a positive minimum —
     * it never checked that the tool could actually carry the question's window. Before the
     * #1677/#1675 fix, {@code getRevenueByCustomer} (q09, a twelve-month window) and {@code
     * getVendorSpend} (q15/q17, six-month windows) only accepted a single {@code YYYY-MM}/{@code
     * YYYY} {@code period}, so the plans' claimed one/two calls were unachievable and this gap
     * would have let that regress silently.
     *
     * <p>For every question whose {@code window.shape} is {@code calendar} or {@code rolling} (a
     * {@code point-in-time} window is a single as-of instant with nothing to range over, so it
     * keeps only the name check above), every tool named in {@code min_tool_calls} must be able to
     * receive that window in one call: it must declare a {@code startDate} *and* {@code endDate}
     * parameter pair, or an {@code asOfDate} parameter (the point-in-time companion leg, e.g. q09's
     * aged-receivables balance sitting alongside its revenue window).
     *
     * <p>Parameter names are read via {@link Parameter#getName()}, which only returns the real
     * source name when the module is compiled with {@code -parameters}. This reactor's parent,
     * {@code spring-boot-starter-parent}, turns that on by default for every module (verified below
     * before the names are trusted, rather than assumed). If that default is ever overridden and
     * names stop being retained, this falls back to {@code facade-contract.yaml}'s configured URI
     * template placeholders ({@code {startDate}}, {@code {endDate}}, {@code {asOfDate}}) — the
     * same names each tool's own downstream request is built from — since {@link
     * org.springframework.ai.tool.annotation.ToolParam} carries no name attribute of its own (only
     * {@code description}/{@code required}) to fall back to instead.
     */
    @Test
    @DisplayName("expected_plan tools can take a calendar/rolling question's window in one call")
    void expectedPlanToolsCanReceiveTheWindowInOneCall() throws IOException {
        Map<String, Method> toolMethodsByName = facadeToolMethodsByName();
        assertThat(toolMethodsByName)
                .as("at least one @Tool method must be found by reflection")
                .isNotEmpty();

        boolean parameterNamesRetained = toolMethodsByName.values().stream()
                        .flatMap(method -> Arrays.stream(method.getParameters()))
                        .findAny()
                        .isPresent()
                && toolMethodsByName.values().stream()
                        .flatMap(method -> Arrays.stream(method.getParameters()))
                        .allMatch(Parameter::isNamePresent);
        Map<String, Set<String>> fallbackParamNamesByTool =
                parameterNamesRetained ? Map.of() : facadeTemplateVariablesByToolName();

        for (JsonNode q : questions().get("questions")) {
            String id = q.path("fixture_id").asText();
            JsonNode plan = q.get("expected_plan");
            if (plan == null || plan.isNull()) {
                continue;
            }
            String shape = q.path("window").path("shape").asText();
            if (!WINDOW_SHAPES_WITH_A_RANGE.contains(shape)) {
                continue;
            }

            Iterator<String> toolNames = plan.get("min_tool_calls").fieldNames();
            while (toolNames.hasNext()) {
                String tool = toolNames.next();
                Set<String> paramNames = parameterNamesRetained
                        ? nameSet(toolMethodsByName.get(tool))
                        : fallbackParamNamesByTool.getOrDefault(tool, Set.of());

                boolean hasStart = paramNames.contains("startDate");
                boolean hasEnd = paramNames.contains("endDate");
                boolean hasAsOf = paramNames.contains("asOfDate");
                if ((hasStart && hasEnd) || hasAsOf) {
                    continue;
                }

                String missing;
                if (!hasStart && !hasEnd) {
                    missing = "startDate and endDate";
                } else if (!hasStart) {
                    missing = "startDate";
                } else {
                    missing = "endDate";
                }
                fail(
                        "%s: expected_plan tool '%s' cannot take a %s window in one call — missing %s "
                                + "(and no asOfDate); declared params were %s",
                        id, tool, shape, missing, paramNames);
            }
        }
    }

    // ─── helpers ──────────────────────────────────────────────────────────

    /**
     * Bare {@code @Tool} method names (the part after the last {@code .}) declared in {@code
     * facade-contract.yaml} — e.g. {@code "WorkorderFacadeTool.searchWorkorders"} contributes
     * {@code "searchWorkorders"}. Composition entries (nested under a top-level key's own dotted
     * name) are covered the same way since the manifest keys every entry, leg or not, at the top
     * level.
     */
    @SuppressWarnings("unchecked")
    private static Set<String> facadeToolMethodNames() throws IOException {
        assertThat(Files.isRegularFile(FACADE_CONTRACT))
                .as("facade-contract.yaml must exist: %s", FACADE_CONTRACT)
                .isTrue();
        Set<String> names = new LinkedHashSet<>();
        try (InputStream stream = Files.newInputStream(FACADE_CONTRACT)) {
            Map<String, Object> raw = new Yaml().load(stream);
            for (String key : raw.keySet()) {
                int dot = key.lastIndexOf('.');
                names.add(dot >= 0 ? key.substring(dot + 1) : key);
            }
        }
        return names;
    }

    /** Every {@code @Tool} method of every {@link #FACADE_TOOL_CLASSES}, keyed by its bare name. */
    private static Map<String, Method> facadeToolMethodsByName() {
        Map<String, Method> methods = new LinkedHashMap<>();
        for (Class<?> facade : FACADE_TOOL_CLASSES) {
            for (Method method : facade.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Tool.class)) {
                    methods.put(method.getName(), method);
                }
            }
        }
        return methods;
    }

    private static Set<String> nameSet(Method method) {
        assertThat(method).as("facade @Tool method resolved by reflection").isNotNull();
        Set<String> names = new LinkedHashSet<>();
        for (Parameter parameter : method.getParameters()) {
            names.add(parameter.getName());
        }
        return names;
    }

    /**
     * Fallback source of parameter names when {@code -parameters} is not in effect: the query
     * placeholders ({@code {startDate}}, etc.) in each {@code facade-contract.yaml} entry's own
     * {@code template}, unioned with its composition {@code legs}' templates when it has no
     * top-level template of its own (a {@code COMPOSITE} entry).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> facadeTemplateVariablesByToolName() throws IOException {
        Map<String, Set<String>> variables = new LinkedHashMap<>();
        try (InputStream stream = Files.newInputStream(FACADE_CONTRACT)) {
            Map<String, Map<String, Object>> raw = new Yaml().load(stream);
            raw.forEach((key, fields) -> {
                int dot = key.lastIndexOf('.');
                String bareName = dot >= 0 ? key.substring(dot + 1) : key;
                variables.put(bareName, templateVariables(fields));
            });
        }
        return variables;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> templateVariables(Map<String, Object> fields) {
        Set<String> vars = new LinkedHashSet<>();
        Object template = fields.get("template");
        if (template instanceof String templateString) {
            Matcher matcher = TEMPLATE_PLACEHOLDER.matcher(templateString);
            while (matcher.find()) {
                vars.add(matcher.group(1));
            }
        }
        Object legs = fields.get("legs");
        if (legs instanceof Map<?, ?> legMap) {
            for (Object legFields : legMap.values()) {
                vars.addAll(templateVariables((Map<String, Object>) legFields));
            }
        }
        return vars;
    }

    private static JsonNode questions() throws IOException {
        assertThat(Files.isRegularFile(QUESTIONS))
                .as("versioned question set must exist: %s", QUESTIONS)
                .isTrue();
        JsonNode root = MAPPER.readTree(QUESTIONS.toFile());
        assertThat(root.path("schema_version").asInt())
                .as("%s: schema_version must be 1", QUESTIONS)
                .isEqualTo(1);
        assertThat(root.path("questions").isArray())
                .as("%s: questions[] required", QUESTIONS)
                .isTrue();
        return root;
    }

    private static Set<String> expectedSections() throws IOException {
        Pattern heading = Pattern.compile("^## (Q\\d+)\\b", Pattern.MULTILINE);
        Matcher m = heading.matcher(Files.readString(EXPECTED, StandardCharsets.UTF_8));
        Set<String> sections = new LinkedHashSet<>();
        while (m.find()) {
            sections.add(m.group(1));
        }
        assertThat(sections)
                .as("EXPECTED.md must carry one '## QN' section per question")
                .hasSize(PLAN_QUESTION_COUNT);
        return sections;
    }
}
