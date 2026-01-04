package com.pos.agent.story.transformation;

import com.pos.agent.story.models.Requirement;
import com.pos.agent.story.models.Requirement.EarsPattern;
import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test for EARS phrasing consistency.
 * Validates that all EARS patterns consistently use "the system shall" phrasing.
 * 
 * Feature: upgrade-story-quality, Property 13: EARS phrasing consistency
 * Validates: Requirements 6.5
 */
class EarsPhrasingConsistencyPropertyTest {

    private final EarsPatternTransformer transformer = new EarsPatternTransformer();

    /**
     * Property 13: EARS phrasing consistency
     * For any generated EARS statement, the text should contain the phrase "the system shall"
     * 
     * Validates: Requirements 6.5
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 13: EARS phrasing consistency")
    void allEarsPatterns_ContainSystemShallPhrase(@ForAll("requirements") Requirement requirement) {
        // Transform the requirement using EARS pattern
        String transformed = transformer.transformRequirement(requirement);
        
        // Verify the transformed text contains "the system shall"
        assertTrue(transformed.contains("the system SHALL"),
                  "EARS pattern " + requirement.getPattern() + " must contain 'the system SHALL'. Got: " + transformed);
    }

    /**
     * Property 13 (variant): EARS phrasing consistency across all pattern types
     * For any requirement with any EARS pattern, the transformation should produce consistent phrasing
     * 
     * Validates: Requirements 6.5
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 13: EARS phrasing consistency (all patterns)")
    void allPatternTypes_ProduceConsistentPhrasing(
            @ForAll("requirementTexts") String text,
            @ForAll EarsPattern pattern) {
        
        Requirement requirement = new Requirement(text, pattern, true);
        String transformed = transformer.transformRequirement(requirement);
        
        // All patterns must contain "the system SHALL"
        assertTrue(transformed.contains("the system SHALL"),
                  "Pattern " + pattern + " must contain 'the system SHALL'");
        
        // Verify pattern-specific prefixes
        switch (pattern) {
            case UBIQUITOUS -> assertTrue(transformed.startsWith("THE"),
                    "Ubiquitous pattern must start with 'THE'");
            case STATE_DRIVEN -> assertTrue(transformed.startsWith("WHILE"),
                    "State-driven pattern must start with 'WHILE'");
            case EVENT_DRIVEN -> assertTrue(transformed.startsWith("WHEN"),
                    "Event-driven pattern must start with 'WHEN'");
            case UNWANTED -> assertTrue(transformed.startsWith("IF"),
                    "Unwanted pattern must start with 'IF'");
        }
    }

    /**
     * Property 13 (variant): Case consistency
     * The phrase "the system shall" should always use lowercase "the system" and uppercase "SHALL"
     * 
     * Validates: Requirements 6.5
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 13: EARS phrasing case consistency")
    void systemShallPhrase_HasConsistentCasing(@ForAll("requirements") Requirement requirement) {
        String transformed = transformer.transformRequirement(requirement);
        
        // Should contain "the system SHALL" (not "THE SYSTEM SHALL" or "the system shall")
        assertTrue(transformed.contains("the system SHALL"),
                  "Must use 'the system SHALL' with consistent casing");
        
        // Should not contain incorrect casings
        assertFalse(transformed.contains("THE SYSTEM SHALL"),
                   "Should not use all caps 'THE SYSTEM SHALL'");
        assertFalse(transformed.contains("the system shall"),
                   "Should not use all lowercase 'the system shall'");
    }

    /**
     * Property 13 (variant): Multiple requirements maintain consistency
     * When transforming multiple requirements, all should maintain consistent phrasing
     * 
     * Validates: Requirements 6.5
     */
    @Property(tries = 50)
    @Label("Feature: upgrade-story-quality, Property 13: EARS phrasing consistency (batch)")
    void multipleRequirements_MaintainConsistentPhrasing(@ForAll("requirementLists") java.util.List<Requirement> requirements) {
        Assume.that(!requirements.isEmpty());
        
        java.util.List<String> transformed = transformer.transformRequirements(requirements);
        
        // All transformed requirements must contain "the system SHALL"
        for (int i = 0; i < transformed.size(); i++) {
            String result = transformed.get(i);
            assertTrue(result.contains("the system SHALL"),
                      "Requirement " + i + " must contain 'the system SHALL'. Got: " + result);
        }
    }

    // ========== Generators ==========

    @Provide
    Arbitrary<Requirement> requirements() {
        return Arbitraries.of(EarsPattern.values())
                .flatMap(pattern -> requirementTexts().map(text -> 
                        new Requirement(text, pattern, true)));
    }

    @Provide
    Arbitrary<String> requirementTexts() {
        return Arbitraries.of(
                "validate user input",
                "process the request",
                "store data in database",
                "send notification to user",
                "log the error",
                "authenticate the user",
                "authorize the action",
                "calculate the total",
                "generate the report",
                "update the record",
                "delete the entry",
                "retrieve the data",
                "transform the input",
                "validate the format",
                "check the permissions",
                "verify the signature",
                "encrypt the data",
                "decrypt the message",
                "compress the file",
                "decompress the archive",
                "when user clicks button, save data",
                "while processing, validate input",
                "if error occurs, log details",
                "on timeout, retry operation",
                "after validation, proceed",
                "during initialization, load config",
                "in case of failure, rollback",
                "upon receiving request, authenticate"
        );
    }

    @Provide
    Arbitrary<java.util.List<Requirement>> requirementLists() {
        return requirements().list().ofMinSize(1).ofMaxSize(10);
    }
}
