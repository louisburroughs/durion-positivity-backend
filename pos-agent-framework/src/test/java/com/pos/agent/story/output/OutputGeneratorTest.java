package com.pos.agent.story.output;

import com.pos.agent.story.models.GherkinScenario;
import com.pos.agent.story.models.OpenQuestion;
import com.pos.agent.story.models.TransformedRequirements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OutputGenerator.
 * 
 * Tests output generation including:
 * - Section ordering
 * - Original content preservation
 * - Markdown formatting
 * 
 * Requirements: 3.3, 3.5, 9.1
 */
class OutputGeneratorTest {
    
    private OutputGenerator generator;
    
    @BeforeEach
    void setUp() {
        generator = new OutputGenerator();
    }
    
    // ========== Section Ordering Tests ==========
    
    @Test
    void testGenerateOutput_AllSectionsInCorrectOrder() {
        TransformedRequirements transformed = createFullTransformedRequirements();
        String originalStory = "Original story text";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify all sections are present
        assertTrue(output.contains("# Test Story"));
        assertTrue(output.contains("## Intent"));
        assertTrue(output.contains("## Actors and Stakeholders"));
        assertTrue(output.contains("## Preconditions"));
        assertTrue(output.contains("## Functional Requirements"));
        assertTrue(output.contains("## Alternate and Error Flows"));
        assertTrue(output.contains("## Business Rules"));
        assertTrue(output.contains("## Data Requirements"));
        assertTrue(output.contains("## Acceptance Criteria"));
        assertTrue(output.contains("## Observability and Audit Requirements"));
        assertTrue(output.contains("## Open Questions"));
        assertTrue(output.contains("## Original Story (Unmodified – For Traceability)"));
        
        // Verify order
        int headerIndex = output.indexOf("# Test Story");
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
        
        assertTrue(headerIndex < intentIndex);
        assertTrue(intentIndex < actorsIndex);
        assertTrue(actorsIndex < preconditionsIndex);
        assertTrue(preconditionsIndex < functionalIndex);
        assertTrue(functionalIndex < alternateIndex);
        assertTrue(alternateIndex < businessIndex);
        assertTrue(businessIndex < dataIndex);
        assertTrue(dataIndex < acceptanceIndex);
        assertTrue(acceptanceIndex < observabilityIndex);
        assertTrue(observabilityIndex < openQuestionsIndex);
        assertTrue(openQuestionsIndex < originalIndex);
    }
    
    @Test
    void testGenerateOutput_EmptySectionsOmitted() {
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        String originalStory = "Original story text";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify only required sections are present
        assertTrue(output.contains("# Test Story"));
        assertTrue(output.contains("## Intent"));
        assertTrue(output.contains("## Original Story"));
        
        // Verify empty sections are omitted
        assertFalse(output.contains("## Actors and Stakeholders"));
        assertFalse(output.contains("## Preconditions"));
        assertFalse(output.contains("## Functional Requirements"));
        assertFalse(output.contains("## Open Questions"));
    }
    
    @Test
    void testGenerateOutput_OriginalStoryIsLast() {
        TransformedRequirements transformed = createFullTransformedRequirements();
        String originalStory = "Original story text";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        int originalIndex = output.indexOf("## Original Story");
        String afterOriginal = output.substring(originalIndex + 20);
        
        // No other ## headers after original story
        assertFalse(afterOriginal.contains("\n## "));
    }
    
    // ========== Original Content Preservation Tests ==========
    
    @Test
    void testGenerateOutput_PreservesOriginalStoryVerbatim() {
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        String originalStory = "As a user, I want to create an order\nso that I can purchase products.";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify original story appears verbatim
        assertTrue(output.contains(originalStory));
    }
    
    @Test
    void testGenerateOutput_PreservesSpecialCharacters() {
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        String originalStory = "Story with <html> tags & special chars: @#$%^&*()";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify special characters are not escaped
        assertTrue(output.contains(originalStory));
    }
    
    @Test
    void testGenerateOutput_PreservesMarkdown() {
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        String originalStory = "# Heading\n**Bold** and *italic*\n- List item";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify markdown is preserved
        assertTrue(output.contains(originalStory));
    }
    
    @Test
    void testGenerateOutput_PreservesMultipleNewlines() {
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        String originalStory = "Line 1\n\n\nLine 2";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify newlines are preserved
        assertTrue(output.contains(originalStory));
    }
    
    @Test
    void testGenerateOutput_HandlesEmptyOriginalStory() {
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        String originalStory = "";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify original story section is present even if empty
        assertTrue(output.contains("## Original Story (Unmodified – For Traceability)"));
    }
    
    // ========== Markdown Formatting Tests ==========
    
    @Test
    void testGenerateOutput_FormatsListsCorrectly() {
        TransformedRequirements transformed = new TransformedRequirements(
                "# Test",
                "Intent",
                List.of("Actor 1", "Actor 2", "Actor 3"),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        String originalStory = "Original";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify list formatting
        assertTrue(output.contains("- Actor 1"));
        assertTrue(output.contains("- Actor 2"));
        assertTrue(output.contains("- Actor 3"));
    }
    
    @Test
    void testGenerateOutput_FormatsGherkinScenariosCorrectly() {
        GherkinScenario scenario = new GherkinScenario(
                "Create order",
                List.of("user is logged in", "cart has items"),
                List.of("user clicks checkout"),
                List.of("order is created", "confirmation is shown")
        );
        
        TransformedRequirements transformed = new TransformedRequirements(
                "# Test",
                "Intent",
                Collections.emptyList(),
                Collections.emptyList(),
                List.of(scenario),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        String originalStory = "Original";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify Gherkin formatting
        assertTrue(output.contains("**Scenario:** Create order"));
        assertTrue(output.contains("**Given** user is logged in"));
        assertTrue(output.contains("**And** cart has items"));
        assertTrue(output.contains("**When** user clicks checkout"));
        assertTrue(output.contains("**Then** order is created"));
        assertTrue(output.contains("**And** confirmation is shown"));
    }
    
    @Test
    void testGenerateOutput_FormatsOpenQuestionsCorrectly() {
        OpenQuestion question = new OpenQuestion(
                "What are the required fields?",
                "Required fields determine data completeness",
                "Without clear field requirements, implementation may be incomplete"
        );
        
        TransformedRequirements transformed = new TransformedRequirements(
                "# Test",
                "Intent",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                List.of(question)
        );
        String originalStory = "Original";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify open question formatting
        assertTrue(output.contains("**Question:** What are the required fields?"));
        assertTrue(output.contains("**Why it matters:** Required fields determine data completeness"));
        assertTrue(output.contains("**Impact:** Without clear field requirements"));
    }
    
    @Test
    void testGenerateOutput_SeparatesOpenQuestionsWithHorizontalRule() {
        OpenQuestion question1 = new OpenQuestion("Question 1?", "Matters 1", "Impact 1");
        OpenQuestion question2 = new OpenQuestion("Question 2?", "Matters 2", "Impact 2");
        
        TransformedRequirements transformed = new TransformedRequirements(
                "# Test",
                "Intent",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                List.of(question1, question2)
        );
        String originalStory = "Original";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify separator between questions
        assertTrue(output.contains("---"));
    }
    
    // ========== Validation Tests ==========
    
    @Test
    void testValidateOutput_ValidOutput() {
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        String originalStory = "Original story text";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        assertTrue(generator.validateOutput(output));
    }
    
    @Test
    void testValidateOutput_NullOutput() {
        assertFalse(generator.validateOutput(null));
    }
    
    @Test
    void testValidateOutput_EmptyOutput() {
        assertFalse(generator.validateOutput(""));
    }
    
    @Test
    void testValidateOutput_MissingOriginalStorySection() {
        String output = "# Test\n## Intent\nSome content";
        
        assertFalse(generator.validateOutput(output));
    }
    
    @Test
    void testValidateOutput_OriginalStoryNotLast() {
        String output = "# Test\n## Original Story\nOriginal\n## Another Section\nContent";
        
        assertFalse(generator.validateOutput(output));
    }
    
    // ========== Error Handling Tests ==========
    
    @Test
    void testGenerateOutput_NullTransformedRequirements() {
        assertThrows(IllegalArgumentException.class, () -> {
            generator.generateOutput(null, "Original story");
        });
    }
    
    @Test
    void testGenerateOutput_NullOriginalStory() {
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        
        assertThrows(IllegalArgumentException.class, () -> {
            generator.generateOutput(transformed, null);
        });
    }
    
    // ========== Edge Cases ==========
    
    @Test
    void testGenerateOutput_VeryLongOriginalStory() {
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        String originalStory = "A".repeat(10000);
        
        String output = generator.generateOutput(transformed, originalStory);
        
        assertTrue(output.contains(originalStory));
    }
    
    @Test
    void testGenerateOutput_MultipleGherkinScenarios() {
        GherkinScenario scenario1 = new GherkinScenario(
                "Scenario 1",
                List.of("Given 1"),
                List.of("When 1"),
                List.of("Then 1")
        );
        GherkinScenario scenario2 = new GherkinScenario(
                "Scenario 2",
                List.of("Given 2"),
                List.of("When 2"),
                List.of("Then 2")
        );
        
        TransformedRequirements transformed = new TransformedRequirements(
                "# Test",
                "Intent",
                Collections.emptyList(),
                Collections.emptyList(),
                List.of(scenario1, scenario2),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
        String originalStory = "Original";
        
        String output = generator.generateOutput(transformed, originalStory);
        
        assertTrue(output.contains("Scenario 1"));
        assertTrue(output.contains("Scenario 2"));
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
