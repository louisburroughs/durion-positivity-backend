package com.pos.agent.story.output;

import com.pos.agent.story.models.GherkinScenario;
import com.pos.agent.story.models.OpenQuestion;
import com.pos.agent.story.models.TransformedRequirements;
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test for mandatory section ordering.
 * Validates that all sections appear in the specified order.
 * 
 * Feature: upgrade-story-quality, Property 9: Mandatory section ordering
 * Validates: Requirements 3.5, 4.1-4.12
 */
class MandatorySectionOrderingPropertyTest {

    private final OutputGenerator generator = new OutputGenerator();

    /**
     * Property 9: Mandatory section ordering
     * For any generated output, all sections should appear in the specified order.
     * 
     * Order: header, intent, actors, preconditions, functional requirements, 
     * alternate flows, business rules, data requirements, acceptance criteria, 
     * observability, open questions (if present), original story
     * 
     * Validates: Requirements 3.5, 4.1-4.12
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 9: Mandatory section ordering")
    void allSections_AppearInCorrectOrder(@ForAll("transformedRequirements") TransformedRequirements transformed) {
        
        String originalStory = "Original story text";
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify section ordering by checking indices
        int headerIndex = output.indexOf("# ");
        int intentIndex = output.indexOf("## Intent");
        int actorsIndex = output.indexOf("## Actors and Stakeholders");
        int preconditionsIndex = output.indexOf("## Preconditions");
        int functionalIndex = output.indexOf("## Functional Requirements");
        int alternateIndex = output.indexOf("## Alternate and Error Flows");
        int businessIndex = output.indexOf("## Business Rules");
        int dataIndex = output.indexOf("## Data Requirements");
        int acceptanceIndex = output.indexOf("## Acceptance Criteria");
        int observabilityIndex = output.indexOf("## Observability and Audit Requirements");
        int openQuestionsIndex = output.indexOf("## Open Questions");
        int originalIndex = output.indexOf("## Original Story");
        
        // Header should be first (if present)
        if (headerIndex >= 0) {
            assertTrue(headerIndex < intentIndex || intentIndex < 0,
                      "Header should come before Intent");
        }
        
        // Intent should come before other sections
        if (intentIndex >= 0) {
            if (actorsIndex >= 0) assertTrue(intentIndex < actorsIndex, "Intent before Actors");
            if (preconditionsIndex >= 0) assertTrue(intentIndex < preconditionsIndex, "Intent before Preconditions");
            if (functionalIndex >= 0) assertTrue(intentIndex < functionalIndex, "Intent before Functional");
        }
        
        // Actors should come before later sections
        if (actorsIndex >= 0) {
            if (preconditionsIndex >= 0) assertTrue(actorsIndex < preconditionsIndex, "Actors before Preconditions");
            if (functionalIndex >= 0) assertTrue(actorsIndex < functionalIndex, "Actors before Functional");
        }
        
        // Preconditions should come before later sections
        if (preconditionsIndex >= 0) {
            if (functionalIndex >= 0) assertTrue(preconditionsIndex < functionalIndex, "Preconditions before Functional");
            if (alternateIndex >= 0) assertTrue(preconditionsIndex < alternateIndex, "Preconditions before Alternate");
        }
        
        // Functional requirements should come before later sections
        if (functionalIndex >= 0) {
            if (alternateIndex >= 0) assertTrue(functionalIndex < alternateIndex, "Functional before Alternate");
            if (businessIndex >= 0) assertTrue(functionalIndex < businessIndex, "Functional before Business");
        }
        
        // Alternate flows should come before later sections
        if (alternateIndex >= 0) {
            if (businessIndex >= 0) assertTrue(alternateIndex < businessIndex, "Alternate before Business");
            if (dataIndex >= 0) assertTrue(alternateIndex < dataIndex, "Alternate before Data");
        }
        
        // Business rules should come before later sections
        if (businessIndex >= 0) {
            if (dataIndex >= 0) assertTrue(businessIndex < dataIndex, "Business before Data");
            if (acceptanceIndex >= 0) assertTrue(businessIndex < acceptanceIndex, "Business before Acceptance");
        }
        
        // Data requirements should come before later sections
        if (dataIndex >= 0) {
            if (acceptanceIndex >= 0) assertTrue(dataIndex < acceptanceIndex, "Data before Acceptance");
            if (observabilityIndex >= 0) assertTrue(dataIndex < observabilityIndex, "Data before Observability");
        }
        
        // Acceptance criteria should come before later sections
        if (acceptanceIndex >= 0) {
            if (observabilityIndex >= 0) assertTrue(acceptanceIndex < observabilityIndex, "Acceptance before Observability");
            if (openQuestionsIndex >= 0) assertTrue(acceptanceIndex < openQuestionsIndex, "Acceptance before Open Questions");
        }
        
        // Observability should come before later sections
        if (observabilityIndex >= 0) {
            if (openQuestionsIndex >= 0) assertTrue(observabilityIndex < openQuestionsIndex, "Observability before Open Questions");
            assertTrue(observabilityIndex < originalIndex, "Observability before Original");
        }
        
        // Open questions should come before original story (if present)
        if (openQuestionsIndex >= 0) {
            assertTrue(openQuestionsIndex < originalIndex, "Open Questions before Original");
        }
        
        // Original story should be last
        assertTrue(originalIndex > 0, "Original story should be present");
    }

    /**
     * Property 9 (variant): Original story is always last
     * For any output, the original story section should be the last section.
     * 
     * Validates: Requirements 4.12
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 9: Original story last")
    void originalStory_IsAlwaysLast(@ForAll("transformedRequirements") TransformedRequirements transformed) {
        
        String originalStory = "Original story text";
        String output = generator.generateOutput(transformed, originalStory);
        
        int originalIndex = output.indexOf("## Original Story");
        assertTrue(originalIndex > 0, "Original story should be present");
        
        // No other ## headers should appear after original story
        String afterOriginal = output.substring(originalIndex + 20);
        assertFalse(afterOriginal.contains("\n## "),
                   "No sections should appear after original story");
    }

    /**
     * Property 9 (variant): Intent is always second (after header)
     * For any output, the intent section should come right after the header.
     * 
     * Validates: Requirements 4.2
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 9: Intent is second")
    void intent_IsSecondSection(@ForAll("transformedRequirements") TransformedRequirements transformed) {
        
        String originalStory = "Original story text";
        String output = generator.generateOutput(transformed, originalStory);
        
        int headerIndex = output.indexOf("# ");
        int intentIndex = output.indexOf("## Intent");
        
        assertTrue(intentIndex > headerIndex, "Intent should come after header");
        
        // No other ## sections between header and intent
        String betweenHeaderAndIntent = output.substring(headerIndex, intentIndex);
        int otherSectionCount = betweenHeaderAndIntent.split("## ").length - 1;
        assertEquals(0, otherSectionCount, "No other sections between header and intent");
    }

    /**
     * Property 9 (variant): Empty sections are omitted but order is preserved
     * For any output with some empty sections, non-empty sections should maintain order.
     * 
     * Validates: Requirements 3.5
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 9: Empty sections omitted")
    void emptySections_AreOmittedButOrderPreserved(
            @ForAll("transformedWithEmptySections") TransformedRequirements transformed) {
        
        String originalStory = "Original story text";
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify that present sections maintain correct order
        List<Integer> sectionIndices = new ArrayList<>();
        
        int actorsIndex = output.indexOf("## Actors and Stakeholders");
        if (actorsIndex >= 0) sectionIndices.add(actorsIndex);
        
        int preconditionsIndex = output.indexOf("## Preconditions");
        if (preconditionsIndex >= 0) sectionIndices.add(preconditionsIndex);
        
        int functionalIndex = output.indexOf("## Functional Requirements");
        if (functionalIndex >= 0) sectionIndices.add(functionalIndex);
        
        int businessIndex = output.indexOf("## Business Rules");
        if (businessIndex >= 0) sectionIndices.add(businessIndex);
        
        // Verify indices are in ascending order
        for (int i = 1; i < sectionIndices.size(); i++) {
            assertTrue(sectionIndices.get(i - 1) < sectionIndices.get(i),
                      "Section indices should be in ascending order");
        }
    }

    /**
     * Property 9 (variant): Open questions appear before original story
     * For any output with open questions, they should appear before original story.
     * 
     * Validates: Requirements 4.11, 4.12
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 9: Open questions before original")
    void openQuestions_AppearBeforeOriginalStory(
            @ForAll("transformedWithOpenQuestions") TransformedRequirements transformed) {
        
        String originalStory = "Original story text";
        String output = generator.generateOutput(transformed, originalStory);
        
        int openQuestionsIndex = output.indexOf("## Open Questions");
        int originalIndex = output.indexOf("## Original Story");
        
        if (openQuestionsIndex >= 0) {
            assertTrue(openQuestionsIndex < originalIndex,
                      "Open questions should appear before original story");
        }
    }

    // ========== Generators ==========

    @Provide
    Arbitrary<TransformedRequirements> transformedRequirements() {
        return Arbitraries.of(
                createFullTransformedRequirements(),
                createMinimalTransformedRequirements(),
                createTransformedWithSomeSections()
        );
    }

    @Provide
    Arbitrary<TransformedRequirements> transformedWithEmptySections() {
        return Arbitraries.of(
                new TransformedRequirements(
                        "# Test",
                        "Intent",
                        List.of("Actor 1"),
                        Collections.emptyList(), // Empty preconditions
                        Collections.emptyList(), // Empty functional
                        Collections.emptyList(), // Empty alternate
                        List.of("Business rule 1"),
                        Collections.emptyList(), // Empty data
                        Collections.emptyList(), // Empty acceptance
                        Collections.emptyList(), // Empty observability
                        Collections.emptyList()  // Empty open questions
                ),
                new TransformedRequirements(
                        "# Test",
                        "Intent",
                        Collections.emptyList(), // Empty actors
                        List.of("Precondition 1"),
                        Collections.emptyList(), // Empty functional
                        List.of("Alternate flow 1"),
                        Collections.emptyList(), // Empty business
                        Collections.emptyList(), // Empty data
                        Collections.emptyList(), // Empty acceptance
                        Collections.emptyList(), // Empty observability
                        Collections.emptyList()  // Empty open questions
                )
        );
    }

    @Provide
    Arbitrary<TransformedRequirements> transformedWithOpenQuestions() {
        return Arbitraries.just(
                new TransformedRequirements(
                        "# Test",
                        "Intent",
                        List.of("Actor 1"),
                        List.of("Precondition 1"),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        List.of("Business rule 1"),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        List.of(new OpenQuestion(
                                "What are the required fields?",
                                "Required fields determine data completeness",
                                "Without clear field requirements, implementation may be incomplete"
                        ))
                )
        );
    }

    // ========== Helper Methods ==========

    private TransformedRequirements createFullTransformedRequirements() {
        return new TransformedRequirements(
                "# Test Story",
                "Test intent",
                List.of("Actor 1", "Actor 2"),
                List.of("Precondition 1", "Precondition 2"),
                List.of(createGherkinScenario("Functional scenario")),
                List.of("Alternate flow 1", "Alternate flow 2"),
                List.of("Business rule 1", "Business rule 2"),
                List.of("Data requirement 1", "Data requirement 2"),
                List.of(createGherkinScenario("Acceptance scenario")),
                List.of("Observability requirement 1"),
                List.of(createOpenQuestion())
        );
    }

    private TransformedRequirements createMinimalTransformedRequirements() {
        return new TransformedRequirements(
                "# Test Story",
                "Test intent",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private TransformedRequirements createTransformedWithSomeSections() {
        return new TransformedRequirements(
                "# Test Story",
                "Test intent",
                List.of("Actor 1"),
                Collections.emptyList(),
                List.of(createGherkinScenario("Functional scenario")),
                Collections.emptyList(),
                List.of("Business rule 1"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    private GherkinScenario createGherkinScenario(String name) {
        return new GherkinScenario(
                name,
                List.of("Given condition"),
                List.of("When action"),
                List.of("Then outcome")
        );
    }

    private OpenQuestion createOpenQuestion() {
        return new OpenQuestion(
                "What are the required fields?",
                "Required fields determine data completeness",
                "Without clear field requirements, implementation may be incomplete"
        );
    }
}
