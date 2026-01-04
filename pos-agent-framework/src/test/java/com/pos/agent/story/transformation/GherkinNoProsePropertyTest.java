package com.pos.agent.story.transformation;

import com.pos.agent.story.models.GherkinScenario;
import com.pos.agent.story.models.Requirement;
import com.pos.agent.story.models.Requirement.EarsPattern;
import net.jqwik.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test for no prose in Gherkin blocks.
 * Validates that generated Gherkin scenarios follow strict Given/When/Then format without free-form prose.
 * 
 * Feature: upgrade-story-quality, Property 11: No prose in Gherkin blocks
 * Validates: Requirements 5.5
 */
class GherkinNoProsePropertyTest {

    private final GherkinScenarioGenerator generator = new GherkinScenarioGenerator();

    /**
     * Property 11: No prose in Gherkin blocks
     * For any generated Gherkin scenario, the structure should follow the strict 
     * Given/When/Then format without free-form prose
     * 
     * Validates: Requirements 5.5
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 11: No prose in Gherkin blocks")
    void generatedScenarios_ContainNoProseBlocks(@ForAll("requirements") Requirement requirement) {
        // Generate Gherkin scenario
        GherkinScenario scenario = generator.generateScenario(requirement);
        
        // Format as string
        String formatted = generator.formatScenario(scenario);
        
        // Every non-empty line must start with a Gherkin keyword
        String[] lines = formatted.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue; // Empty lines are OK
            }
            
            // Line must start with Scenario:, Given, When, Then, or And
            boolean isGherkinLine = trimmed.startsWith("Scenario:") ||
                                   trimmed.startsWith("Given ") ||
                                   trimmed.startsWith("When ") ||
                                   trimmed.startsWith("Then ") ||
                                   trimmed.startsWith("And ");
            
            assertTrue(isGherkinLine,
                      "Line must be a Gherkin statement, not prose. Got: " + trimmed);
        }
    }

    /**
     * Property 11 (variant): No narrative text blocks
     * Scenarios should not contain multi-line narrative or explanatory text
     * 
     * Validates: Requirements 5.5
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 11: No narrative text blocks")
    void generatedScenarios_ContainNoNarrativeBlocks(@ForAll("requirements") Requirement requirement) {
        GherkinScenario scenario = generator.generateScenario(requirement);
        String formatted = generator.formatScenario(scenario);
        
        // Check for common prose indicators
        assertFalse(formatted.contains("In order to"),
                   "Should not contain narrative phrase 'In order to'");
        assertFalse(formatted.contains("As mentioned"),
                   "Should not contain narrative phrase 'As mentioned'");
        assertFalse(formatted.contains("Note that"),
                   "Should not contain narrative phrase 'Note that'");
        assertFalse(formatted.contains("For example"),
                   "Should not contain narrative phrase 'For example'");
    }

    /**
     * Property 11 (variant): Structured format only
     * Each clause should be a single, structured statement
     * 
     * Validates: Requirements 5.5
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 11: Structured format only")
    void generatedScenarios_UseStructuredFormatOnly(@ForAll("requirements") Requirement requirement) {
        GherkinScenario scenario = generator.generateScenario(requirement);
        
        // Verify Given clauses are structured
        for (String given : scenario.getGiven()) {
            assertFalse(given.trim().isEmpty(), "Given clause should not be empty");
            // Should be a single statement, not a paragraph
            assertFalse(given.contains("\n"), "Given clause should be single line");
        }
        
        // Verify When clauses are structured
        for (String when : scenario.getWhen()) {
            assertFalse(when.trim().isEmpty(), "When clause should not be empty");
            assertFalse(when.contains("\n"), "When clause should be single line");
        }
        
        // Verify Then clauses are structured
        for (String then : scenario.getThen()) {
            assertFalse(then.trim().isEmpty(), "Then clause should not be empty");
            assertFalse(then.contains("\n"), "Then clause should be single line");
        }
    }

    /**
     * Property 11 (variant): No unstructured comments
     * Scenarios should not contain comments or annotations mixed with Gherkin
     * 
     * Validates: Requirements 5.5
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 11: No unstructured comments")
    void generatedScenarios_ContainNoComments(@ForAll("requirements") Requirement requirement) {
        GherkinScenario scenario = generator.generateScenario(requirement);
        String formatted = generator.formatScenario(scenario);
        
        // Should not contain comment markers
        assertFalse(formatted.contains("#"),
                   "Should not contain comment markers");
        assertFalse(formatted.contains("//"),
                   "Should not contain comment markers");
        assertFalse(formatted.contains("/*"),
                   "Should not contain comment markers");
    }

    /**
     * Property 11 (variant): Consistent indentation
     * All Gherkin clauses should have consistent indentation (structured format)
     * 
     * Validates: Requirements 5.5
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 11: Consistent indentation")
    void generatedScenarios_HaveConsistentIndentation(@ForAll("requirements") Requirement requirement) {
        GherkinScenario scenario = generator.generateScenario(requirement);
        String formatted = generator.formatScenario(scenario);
        
        String[] lines = formatted.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            
            // Scenario line should not be indented
            if (line.trim().startsWith("Scenario:")) {
                assertFalse(line.startsWith("  "),
                           "Scenario line should not be indented");
            }
            
            // Given/When/Then/And lines should be indented
            if (line.trim().startsWith("Given") || 
                line.trim().startsWith("When") || 
                line.trim().startsWith("Then") || 
                line.trim().startsWith("And")) {
                assertTrue(line.startsWith("  "),
                          "Gherkin clauses should be indented: " + line);
            }
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
                "if authentication fails, reject request and log attempt",
                "In order to access the system, user must authenticate",
                "Note that validation is required before processing",
                "For example, when user submits form, system validates"
        );
    }
}
