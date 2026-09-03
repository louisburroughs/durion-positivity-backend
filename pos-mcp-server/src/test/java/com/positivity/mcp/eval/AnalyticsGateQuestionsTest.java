package com.positivity.mcp.eval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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

    /** Plan §6 is a twenty-question matrix; the file carries all twenty, gated or not. */
    private static final int PLAN_QUESTION_COUNT = 20;

    private static final Set<String> WINDOW_SHAPES = Set.of("calendar", "rolling", "point-in-time", "mixed");

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

    // ─── helpers ──────────────────────────────────────────────────────────

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
