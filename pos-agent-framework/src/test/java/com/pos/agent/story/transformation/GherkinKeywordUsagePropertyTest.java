package com.pos.agent.story.transformation;

import com.pos.agent.story.models.GherkinScenario;
import com.pos.agent.story.models.Requirement;
import com.pos.agent.story.models.Requirement.EarsPattern;
import net.jqwik.api.*;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test for Gherkin keyword usage.
 * Validates that generated Gherkin scenarios only use Given, When, Then, and And keywords.
 * 
 * Feature: upgrade-story-quality, Property 10: Gherkin keyword usage
 * Validates: Requirements 5.1
 */
class GherkinKeywordUsagePropertyTest {

    private final GherkinScenarioGenerator generator = new GherkinScenarioGenerator();
    
    private static final Set<String> VALID_GHERKIN_KEYWORDS = Set.of(
        "Scenario:", "Given", "When", "Then", "And"
    );

    /**
     * Property 10: Gherkin keyword usage
     * For any generated Gherkin scenario, the text should contain only the keywords 
     * Given, When, Then, and And
     * 
     * Validates: Requirements 5.1
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 10: Gherkin keyword usage")
    void generatedScenarios_OnlyUseValidGherkinKeywords(@ForAll("requirements") Requirement requirement) {
        // Generate Gherkin scenario
        GherkinScenario scenario = generator.generateScenario(requirement);
        
        // Format as string
        String formatted = generator.formatScenario(scenario);
        
        // Verify each line starts with a valid Gherkin keyword
        String[] lines = formatted.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue; // Skip empty lines
            }
            
            boolean startsWithValidKeyword = VALID_GHERKIN_KEYWORDS.stream()
                .anyMatch(keyword -> trimmed.startsWith(keyword));
            
            assertTrue(startsWithValidKeyword,
                      "Line must start with valid Gherkin keyword (Scenario:, Given, When, Then, And). Got: " + trimmed);
        }
    }

    /**
     * Property 10 (variant): No invalid keywords
     * Generated scenarios should not contain invalid Gherkin keywords like But, Or, etc.
     * 
     * Validates: Requirements 5.1
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 10: No invalid Gherkin keywords")
    void generatedScenarios_DoNotContainInvalidKeywords(@ForAll("requirements") Requirement requirement) {
        GherkinScenario scenario = generator.generateScenario(requirement);
        String formatted = generator.formatScenario(scenario);
        
        // Check for invalid keywords at line starts
        String[] lines = formatted.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            
            // Should not start with invalid keywords
            assertFalse(trimmed.startsWith("But "),
                       "Should not use 'But' keyword: " + trimmed);
            assertFalse(trimmed.startsWith("Or "),
                       "Should not use 'Or' keyword: " + trimmed);
            assertFalse(trimmed.startsWith("However "),
                       "Should not use 'However' keyword: " + trimmed);
        }
    }

    /**
     * Property 10 (variant): Proper keyword sequence
     * Scenarios should follow proper Gherkin structure: Scenario → Given → When → Then
     * 
     * Validates: Requirements 5.1
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 10: Proper Gherkin keyword sequence")
    void generatedScenarios_FollowProperKeywordSequence(@ForAll("requirements") Requirement requirement) {
        GherkinScenario scenario = generator.generateScenario(requirement);
        String formatted = generator.formatScenario(scenario);
        
        // Should start with "Scenario:"
        assertTrue(formatted.trim().startsWith("Scenario:"),
                  "Scenario should start with 'Scenario:' keyword");
        
        // Extract keyword sequence
        String[] lines = formatted.split("\n");
        StringBuilder keywordSequence = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Given")) keywordSequence.append("G");
            else if (trimmed.startsWith("When")) keywordSequence.append("W");
            else if (trimmed.startsWith("Then")) keywordSequence.append("T");
            else if (trimmed.startsWith("And")) keywordSequence.append("A");
        }
        
        String sequence = keywordSequence.toString();
        
        // Valid sequences: G*W*T*, where * means "followed by zero or more A"
        // Examples: GWT, GAWAT, GWAAT, WT, T, etc.
        // Invalid: WG (When before Given), TW (Then before When), etc.
        
        // Check that Given comes before When (if both exist)
        int givenPos = sequence.indexOf('G');
        int whenPos = sequence.indexOf('W');
        if (givenPos >= 0 && whenPos >= 0) {
            assertTrue(givenPos < whenPos,
                      "Given should come before When. Sequence: " + sequence);
        }
        
        // Check that When comes before Then (if both exist)
        int thenPos = sequence.indexOf('T');
        if (whenPos >= 0 && thenPos >= 0) {
            assertTrue(whenPos < thenPos,
                      "When should come before Then. Sequence: " + sequence);
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
                "when user clicks button, save data",
                "given user is logged in, when user accesses dashboard, then system displays data",
                "if error occurs, log details and notify admin",
                "while processing, validate input and check permissions",
                "the system validates all inputs before processing",
                "user can view their profile information",
                "system authenticates user credentials",
                "when form is submitted, validate fields and store results",
                "given valid credentials, authenticate and create session",
                "if authentication fails, reject request and log attempt"
        );
    }
}
