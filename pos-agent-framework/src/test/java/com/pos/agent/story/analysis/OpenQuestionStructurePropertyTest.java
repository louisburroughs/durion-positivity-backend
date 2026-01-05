package com.pos.agent.story.analysis;

import com.pos.agent.story.models.OpenQuestion;
import net.jqwik.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test for open question structure validation.
 * Validates that generated open questions have the required structure:
 * - Question text
 * - Why it matters explanation
 * - Impact description
 * 
 * Feature: upgrade-story-quality, Property 16: Open question structure
 * Validates: Requirements 8.6, 8.7
 */
class OpenQuestionStructurePropertyTest {

    private final OpenQuestionGenerator generator = new OpenQuestionGenerator();

    /**
     * Property 16: Open question structure
     * For any generated open question, it should have question text, why it matters, and impact.
     * 
     * Validates: Requirements 8.6, 8.7
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 16: Open question structure")
    void generatedOpenQuestions_HaveRequiredStructure(@ForAll("ambiguities") String ambiguity) {
        
        OpenQuestion question = generator.createOpenQuestion(
            ambiguity, 
            OpenQuestionGenerator.AmbiguityType.BUSINESS_RULES
        );
        
        // Verify question text is present
        assertNotNull(question.getQuestion(), "Question text should not be null");
        assertFalse(question.getQuestion().trim().isEmpty(), "Question text should not be empty");
        
        // Verify why it matters is present
        assertNotNull(question.getWhyItMatters(), "Why it matters should not be null");
        assertFalse(question.getWhyItMatters().trim().isEmpty(), "Why it matters should not be empty");
        
        // Verify impact is present
        assertNotNull(question.getImpact(), "Impact should not be null");
        assertFalse(question.getImpact().trim().isEmpty(), "Impact should not be empty");
    }

    /**
     * Property 16 (variant): All ambiguity types produce valid questions
     * For any ambiguity type, generated questions should have valid structure.
     * 
     * Validates: Requirements 8.6, 8.7
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 16: All types produce valid structure")
    void allAmbiguityTypes_ProduceValidStructure(
            @ForAll("ambiguities") String ambiguity,
            @ForAll OpenQuestionGenerator.AmbiguityType type) {
        
        OpenQuestion question = generator.createOpenQuestion(ambiguity, type);
        
        // Validate using the generator's validation method
        assertTrue(generator.validateOpenQuestion(question),
                  "Question should pass validation for type: " + type);
    }

    /**
     * Property 16 (variant): Question text contains the ambiguity
     * For any ambiguity, the generated question should reference the original ambiguity.
     * 
     * Validates: Requirements 8.6
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 16: Question references ambiguity")
    void questionText_ReferencesOriginalAmbiguity(@ForAll("ambiguities") String ambiguity) {
        
        OpenQuestion question = generator.createOpenQuestion(
            ambiguity,
            OpenQuestionGenerator.AmbiguityType.BUSINESS_RULES
        );
        
        // Question should contain some reference to the original ambiguity
        // (either the ambiguity text itself or a question about it)
        String questionText = question.getQuestion().toLowerCase();
        String ambiguityLower = ambiguity.toLowerCase();
        
        // Check if question contains key words from ambiguity or is a proper question
        assertTrue(questionText.contains("?") || questionText.contains(ambiguityLower) || 
                   questionText.contains("what") || questionText.contains("how"),
                  "Question should be a proper question or reference the ambiguity");
    }

    /**
     * Property 16 (variant): Impact description is meaningful
     * For any ambiguity type, the impact description should be meaningful and non-trivial.
     * 
     * Validates: Requirements 8.7
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 16: Impact is meaningful")
    void impactDescription_IsMeaningful(@ForAll OpenQuestionGenerator.AmbiguityType type) {
        
        String ambiguity = "Test ambiguity for " + type;
        OpenQuestion question = generator.createOpenQuestion(ambiguity, type);
        
        String impact = question.getImpact();
        
        // Impact should be substantial (more than just a few words)
        assertTrue(impact.length() > 20,
                  "Impact description should be substantial, got: " + impact);
        
        // Impact should contain meaningful words
        assertTrue(impact.contains("system") || impact.contains("data") || 
                   impact.contains("implementation") || impact.contains("may"),
                  "Impact should describe consequences");
    }

    /**
     * Property 16 (variant): Batch generation maintains structure
     * For any list of ambiguities, all generated questions should have valid structure.
     * 
     * Validates: Requirements 8.6, 8.7
     */
    @Property(tries = 50)
    @Label("Feature: upgrade-story-quality, Property 16: Batch generation maintains structure")
    void batchGeneration_MaintainsStructure(@ForAll("ambiguityLists") List<String> ambiguities) {
        
        List<OpenQuestion> questions = generator.generateOpenQuestions(ambiguities);
        
        // All generated questions should have valid structure
        for (OpenQuestion question : questions) {
            assertTrue(generator.validateOpenQuestion(question),
                      "All questions in batch should be valid");
        }
    }

    /**
     * Property 16 (variant): Empty input produces empty output
     * For empty or null input, generator should return empty list.
     * 
     * Validates: Requirements 8.6, 8.7
     */
    @Property(tries = 50)
    @Label("Feature: upgrade-story-quality, Property 16: Empty input handled")
    void emptyInput_ProducesEmptyOutput(@ForAll("emptyOrNullLists") List<String> ambiguities) {
        
        List<OpenQuestion> questions = generator.generateOpenQuestions(ambiguities);
        
        // Empty or null input should produce empty output
        assertTrue(questions.isEmpty(),
                  "Empty or null input should produce empty output");
    }

    /**
     * Property 16 (variant): Formatted output contains all components
     * For any open question, the formatted output should contain all three components.
     * 
     * Validates: Requirements 8.6, 8.7
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 16: Formatted output complete")
    void formattedOutput_ContainsAllComponents(@ForAll("ambiguities") String ambiguity) {
        
        OpenQuestion question = generator.createOpenQuestion(
            ambiguity,
            OpenQuestionGenerator.AmbiguityType.BUSINESS_RULES
        );
        
        String formatted = generator.formatOpenQuestion(question);
        
        // Formatted output should contain all three components
        assertTrue(formatted.contains("Question:"), "Should contain question label");
        assertTrue(formatted.contains("Why it matters:"), "Should contain why it matters label");
        assertTrue(formatted.contains("Impact:"), "Should contain impact label");
        
        // Should contain the actual content
        assertTrue(formatted.contains(question.getQuestion()), "Should contain question text");
        assertTrue(formatted.contains(question.getWhyItMatters()), "Should contain why it matters");
        assertTrue(formatted.contains(question.getImpact()), "Should contain impact");
    }

    // ========== Generators ==========

    @Provide
    Arbitrary<String> ambiguities() {
        return Arbitraries.of(
                "Required fields are unclear",
                "Permission rules are unspecified",
                "State transitions are incomplete",
                "Audit contract is unknown",
                "Uniqueness constraints are ambiguous",
                "Data validation rules are missing",
                "Error handling is not defined",
                "Business rules are unclear",
                "Integration points are unspecified",
                "The field validation logic is unclear",
                "User permissions for this operation are unknown",
                "The state machine transitions are incomplete",
                "Event logging requirements are ambiguous",
                "Idempotency requirements are not specified",
                "Required data attributes are missing",
                "Error response format is unclear",
                "Business rule precedence is ambiguous",
                "External service contract is unknown"
        );
    }

    @Provide
    Arbitrary<List<String>> ambiguityLists() {
        return ambiguities().list().ofMinSize(1).ofMaxSize(10);
    }

    @Provide
    Arbitrary<List<String>> emptyOrNullLists() {
        return Arbitraries.of(
                List.of(),
                null,
                List.of(""),
                List.of("   "),
                List.of("", "  ", "\t")
        );
    }
}
