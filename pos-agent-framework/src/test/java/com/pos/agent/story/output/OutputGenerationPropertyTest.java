package com.pos.agent.story.output;

import com.pos.agent.story.models.GherkinScenario;
import com.pos.agent.story.models.OpenQuestion;
import com.pos.agent.story.models.TransformedRequirements;
import net.jqwik.api.*;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for output generation.
 * Validates that valid inputs produce non-empty output and open questions are included.
 * 
 * Feature: upgrade-story-quality, Property 6: Output generation for valid inputs
 * Feature: upgrade-story-quality, Property 7: Open questions section inclusion
 * Validates: Requirements 3.1, 3.2
 */
class OutputGenerationPropertyTest {

    private final OutputGenerator generator = new OutputGenerator();

    /**
     * Property 6: Output generation for valid inputs
     * For any valid GitHub issue, the system should produce a non-empty rewritten issue body.
     * 
     * Validates: Requirements 3.1
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 6: Output generation for valid inputs")
    void validInputs_ProduceNonEmptyOutput(
            @ForAll("transformedRequirements") TransformedRequirements transformed,
            @ForAll("originalStories") String originalStory) {
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify output is not null or empty
        assertNotNull(output, "Output should not be null");
        assertFalse(output.trim().isEmpty(), "Output should not be empty");
    }

    /**
     * Property 6 (variant): Output contains header and intent
     * For any valid input, the output should contain at minimum a header and intent.
     * 
     * Validates: Requirements 3.1, 4.1, 4.2
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 6: Output contains minimum sections")
    void validInputs_ContainMinimumSections(
            @ForAll("transformedRequirements") TransformedRequirements transformed,
            @ForAll("originalStories") String originalStory) {
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify minimum sections are present
        assertTrue(output.contains(transformed.getHeader()),
                  "Output should contain header");
        assertTrue(output.contains("## Intent"),
                  "Output should contain intent section");
        assertTrue(output.contains("## Original Story"),
                  "Output should contain original story section");
    }

    /**
     * Property 6 (variant): Output is valid markdown
     * For any valid input, the output should be valid markdown structure.
     * 
     * Validates: Requirements 3.1
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 6: Output is valid markdown")
    void validInputs_ProduceValidMarkdown(
            @ForAll("transformedRequirements") TransformedRequirements transformed,
            @ForAll("originalStories") String originalStory) {
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify markdown structure (headers start with #)
        assertTrue(output.contains("# ") || output.contains("## "),
                  "Output should contain markdown headers");
        
        // Verify no malformed markdown
        assertFalse(output.contains("###  "), // Double space after header
                   "Output should not contain malformed headers");
    }

    /**
     * Property 7: Open questions section inclusion
     * For any processing result that identifies ambiguities, 
     * the output should contain an "Open Questions" section.
     * 
     * Validates: Requirements 3.2
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 7: Open questions section inclusion")
    void outputWithAmbiguities_ContainsOpenQuestionsSection(
            @ForAll("transformedWithOpenQuestions") TransformedRequirements transformed) {
        
        String originalStory = "Original story text";
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify open questions section is present
        assertTrue(output.contains("## Open Questions"),
                  "Output should contain Open Questions section when ambiguities exist");
    }

    /**
     * Property 7 (variant): Open questions section contains all questions
     * For any output with open questions, all questions should be included.
     * 
     * Validates: Requirements 3.2
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 7: All questions included")
    void openQuestionsSection_ContainsAllQuestions(
            @ForAll("transformedWithOpenQuestions") TransformedRequirements transformed) {
        
        String originalStory = "Original story text";
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify all open questions are present in output
        for (OpenQuestion question : transformed.getOpenQuestions()) {
            assertTrue(output.contains(question.getQuestion()),
                      "Output should contain question: " + question.getQuestion());
            assertTrue(output.contains(question.getWhyItMatters()),
                      "Output should contain why it matters");
            assertTrue(output.contains(question.getImpact()),
                      "Output should contain impact");
        }
    }

    /**
     * Property 7 (variant): No open questions section when no ambiguities
     * For any output without ambiguities, the open questions section should be omitted.
     * 
     * Validates: Requirements 3.2
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 7: No section when no questions")
    void outputWithoutAmbiguities_OmitsOpenQuestionsSection(
            @ForAll("transformedWithoutOpenQuestions") TransformedRequirements transformed) {
        
        String originalStory = "Original story text";
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify open questions section is not present
        assertFalse(output.contains("## Open Questions"),
                   "Output should not contain Open Questions section when no ambiguities");
    }

    /**
     * Property 7 (variant): Open questions are formatted correctly
     * For any open question, it should be formatted with proper markdown.
     * 
     * Validates: Requirements 3.2, 8.6, 8.7
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 7: Questions formatted correctly")
    void openQuestions_AreFormattedCorrectly(
            @ForAll("transformedWithOpenQuestions") TransformedRequirements transformed) {
        
        String originalStory = "Original story text";
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify formatting includes bold labels
        assertTrue(output.contains("**Question:**"),
                  "Questions should have bold Question label");
        assertTrue(output.contains("**Why it matters:**"),
                  "Questions should have bold Why it matters label");
        assertTrue(output.contains("**Impact:**"),
                  "Questions should have bold Impact label");
    }

    /**
     * Property 6 & 7 combined: Output validation passes
     * For any valid input, the generated output should pass validation.
     * 
     * Validates: Requirements 3.1, 3.2
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 6 & 7: Output validation passes")
    void generatedOutput_PassesValidation(
            @ForAll("transformedRequirements") TransformedRequirements transformed,
            @ForAll("originalStories") String originalStory) {
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify output passes validation
        assertTrue(generator.validateOutput(output),
                  "Generated output should pass validation");
    }

    // ========== Generators ==========

    @Provide
    Arbitrary<TransformedRequirements> transformedRequirements() {
        return Arbitraries.of(
                createFullTransformedRequirements(),
                createMinimalTransformedRequirements(),
                createTransformedWithSomeSections(),
                createTransformedWithOpenQuestions()
        );
    }

    @Provide
    Arbitrary<TransformedRequirements> transformedWithOpenQuestions() {
        return Arbitraries.of(
                new TransformedRequirements(
                        "# Test Story",
                        "Test intent",
                        List.of("Actor 1"),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        Collections.emptyList(),
                        List.of(createOpenQuestion("What are the required fields?"))
                ),
                new TransformedRequirements(
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
                        List.of(
                                createOpenQuestion("What are the required fields?"),
                                createOpenQuestion("What are the permission rules?")
                        )
                )
        );
    }

    @Provide
    Arbitrary<TransformedRequirements> transformedWithoutOpenQuestions() {
        return Arbitraries.of(
                createMinimalTransformedRequirements(),
                createTransformedWithSomeSections()
        );
    }

    @Provide
    Arbitrary<String> originalStories() {
        return Arbitraries.of(
                "As a user, I want to create an order.",
                "The system should validate input.",
                "Simple story text.",
                "Story with multiple lines\nand sections.",
                ""
        );
    }

    // ========== Helper Methods ==========

    private TransformedRequirements createFullTransformedRequirements() {
        return new TransformedRequirements(
                "# Test Story",
                "Test intent",
                List.of("Actor 1", "Actor 2"),
                List.of("Precondition 1"),
                List.of(createGherkinScenario("Functional scenario")),
                List.of("Alternate flow 1"),
                List.of("Business rule 1"),
                List.of("Data requirement 1"),
                List.of(createGherkinScenario("Acceptance scenario")),
                List.of("Observability requirement 1"),
                List.of(createOpenQuestion("What are the required fields?"))
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

    private TransformedRequirements createTransformedWithOpenQuestions() {
        return new TransformedRequirements(
                "# Test Story",
                "Test intent",
                List.of("Actor 1"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                List.of(createOpenQuestion("What are the required fields?"))
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

    private OpenQuestion createOpenQuestion(String questionText) {
        return new OpenQuestion(
                questionText,
                "This matters because it affects implementation",
                "Without this information, implementation may be incorrect"
        );
    }
}
