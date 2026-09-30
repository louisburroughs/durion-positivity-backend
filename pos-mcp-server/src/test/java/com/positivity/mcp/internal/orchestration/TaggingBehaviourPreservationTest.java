package com.positivity.mcp.internal.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.mcp.internal.domain.QuestionTags;
import com.positivity.mcp.internal.domain.RouterClassification;
import com.positivity.mcp.internal.domain.TagSource;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * ADR-0068 spec §4, behaviour preservation. {@code tagging/decisions-fixture.json} holds the
 * decisions the pre-ADR-0068 heuristics took on ~75 en/fr/es messages (captured from the unrefactored
 * code at main 53305a2bd, before any heuristic moved behind the {@code QuestionTagger} seam). Every
 * decision surface the session managers drive must keep taking exactly those decisions.
 */
class TaggingBehaviourPreservationTest {

    record Fixture(
            String id,
            String message,
            boolean simpleChat,
            boolean followsPreviousTurn,
            String workflowState,
            boolean impliesDateWindow,
            boolean needsWebSearch,
            boolean aboutInventory,
            boolean aboutOrders,
            boolean adminFastPath,
            boolean compoundQuestion) {}

    private static List<Fixture> fixtures;
    private static TaggingDecisionSurfaces surfaces;

    @BeforeAll
    static void load() throws Exception {
        try (InputStream in = TaggingBehaviourPreservationTest.class
                .getClassLoader()
                .getResourceAsStream("tagging/decisions-fixture.json")) {
            assertThat(in).as("fixture on the test classpath").isNotNull();
            JsonNode root = new ObjectMapper().readTree(in);
            assertThat(root.path("schema_version").asInt()).isEqualTo(1);
            List<Fixture> loaded = new ArrayList<>();
            for (JsonNode node : root.path("cases")) {
                loaded.add(new Fixture(
                        node.path("id").asText(),
                        node.path("message").asText(),
                        node.path("simpleChat").asBoolean(),
                        node.path("followsPreviousTurn").asBoolean(),
                        node.path("workflowState").asText(),
                        node.path("impliesDateWindow").asBoolean(),
                        node.path("needsWebSearch").asBoolean(),
                        node.path("aboutInventory").asBoolean(),
                        node.path("aboutOrders").asBoolean(),
                        node.path("adminFastPath").asBoolean(),
                        node.path("compoundQuestion").asBoolean()));
            }
            fixtures = List.copyOf(loaded);
        }
        surfaces = new TaggingDecisionSurfaces();
    }

    static Stream<Fixture> fixtures() {
        return fixtures.stream();
    }

    @Test
    @DisplayName("the fixture covers every heuristic in en, fr and es")
    void fixtureCoversEveryHeuristic() {
        assertThat(fixtures).hasSizeGreaterThanOrEqualTo(60);
        assertThat(fixtures).anyMatch(Fixture::simpleChat);
        assertThat(fixtures).anyMatch(Fixture::followsPreviousTurn);
        assertThat(fixtures).anyMatch(f -> f.workflowState().equals("CREATING_PO"));
        assertThat(fixtures).anyMatch(f -> f.workflowState().equals("RECEIVING_ASN"));
        assertThat(fixtures).anyMatch(f -> f.workflowState().equals("INVENTORY_RECON"));
        assertThat(fixtures).anyMatch(Fixture::impliesDateWindow);
        assertThat(fixtures).anyMatch(f -> f.impliesDateWindow() && f.message().contains("\n"));
        assertThat(fixtures).anyMatch(Fixture::needsWebSearch);
        assertThat(fixtures).anyMatch(Fixture::aboutInventory);
        assertThat(fixtures).anyMatch(Fixture::aboutOrders);
        assertThat(fixtures).anyMatch(Fixture::adminFastPath);
        assertThat(fixtures).anyMatch(f -> f.id().startsWith("admin-veto") && !f.adminFastPath());
        assertThat(fixtures).anyMatch(Fixture::compoundQuestion);
        assertThat(fixtures).anyMatch(f -> f.id().startsWith("fr-") || f.id().endsWith("-fr"));
        assertThat(fixtures).anyMatch(f -> f.id().startsWith("es-") || f.id().endsWith("-es"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("the tagged path in mode off takes the pre-refactor decision on every surface")
    void decisionSurfacesArePreserved(Fixture fixture) {
        TaggingDecisionSurfaces.Decisions actual = surfaces.decide(fixture.message());
        assertDecisions(fixture, actual);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("the heuristic tagger answers every tag with the pre-refactor decision")
    void heuristicTaggerIsPreserved(Fixture fixture) {
        QuestionTags tags = surfaces.heuristicTags(fixture.message());
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(tags.simpleChat()).as("simple_chat").isEqualTo(fixture.simpleChat());
        softly.assertThat(tags.followsPreviousTurn())
                .as("follows_previous_turn")
                .isEqualTo(fixture.followsPreviousTurn());
        softly.assertThat(tags.workflowState().name()).as("workflow_state").isEqualTo(fixture.workflowState());
        softly.assertThat(tags.impliesDateWindow()).as("implies_date_window").isEqualTo(fixture.impliesDateWindow());
        softly.assertThat(tags.needsWebSearch()).as("needs_web_search").isEqualTo(fixture.needsWebSearch());
        softly.assertThat(tags.aboutInventory()).as("about_inventory").isEqualTo(fixture.aboutInventory());
        softly.assertThat(tags.aboutOrders()).as("about_orders").isEqualTo(fixture.aboutOrders());
        softly.assertThat(tags.compoundQuestion()).as("compound_question").isEqualTo(fixture.compoundQuestion());
        // The fast path fires on keyword-or-phrase-and-no-veto, which is exactly the tag's heuristic
        // value when the admin tool is in the gated set (as the fixture's surface has it).
        softly.assertThat(tags.adminAccountQuestion())
                .as("admin_account_question")
                .isEqualTo(fixture.adminFastPath());
        softly.assertThat(surfaces.classifierSaysSimpleChat(fixture.message()))
                .as("classifier and tag agree")
                .isEqualTo(tags.simpleChat());
        // Every heuristic answer is certain and heuristic; the router tags are the safe default.
        softly.assertThat(tags.heuristic().values()).allMatch(answer -> answer.confidence() == 1.0);
        softly.assertThat(tags.heuristic().values()).allMatch(answer -> answer.source() == TagSource.HEURISTIC);
        softly.assertThat(tags.routerClassification()).isEqualTo(RouterClassification.safeDefault());
        softly.assertThat(tags.entitySeeds()).isEmpty();
        softly.assertThat(tags.model()).isEmpty();
        softly.assertAll();
    }

    private static void assertDecisions(Fixture fixture, TaggingDecisionSurfaces.Decisions actual) {
        SoftAssertions softly = new SoftAssertions();
        softly.assertThat(actual.simpleChat()).as("simpleChat").isEqualTo(fixture.simpleChat());
        softly.assertThat(actual.followsPreviousTurn())
                .as("followsPreviousTurn")
                .isEqualTo(fixture.followsPreviousTurn());
        softly.assertThat(actual.workflowState()).as("workflowState").isEqualTo(fixture.workflowState());
        softly.assertThat(actual.impliesDateWindow()).as("impliesDateWindow").isEqualTo(fixture.impliesDateWindow());
        softly.assertThat(actual.needsWebSearch()).as("needsWebSearch").isEqualTo(fixture.needsWebSearch());
        softly.assertThat(actual.aboutInventory()).as("aboutInventory").isEqualTo(fixture.aboutInventory());
        softly.assertThat(actual.aboutOrders()).as("aboutOrders").isEqualTo(fixture.aboutOrders());
        softly.assertThat(actual.adminFastPath()).as("adminFastPath").isEqualTo(fixture.adminFastPath());
        softly.assertThat(actual.compoundQuestion()).as("compoundQuestion").isEqualTo(fixture.compoundQuestion());
        softly.assertAll();
    }
}
