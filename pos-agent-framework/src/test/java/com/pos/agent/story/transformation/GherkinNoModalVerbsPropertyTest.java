package com.pos.agent.story.transformation;

import com.pos.agent.story.models.GherkinScenario;
import com.pos.agent.story.models.Requirement;
import com.pos.agent.story.models.Requirement.EarsPattern;
import net.jqwik.api.*;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test for no modal verbs in Gherkin scenarios.
 * Validates that generated Gherkin scenarios do not contain modal verbs 
 * (should, may, might, could, ideally).
 * 
 * Feature: upgrade-story-quality, Property 12: No modal verbs in Gherkin
 * Validates: Requirements 5.6
 */
class GherkinNoModalVerbsPropertyTest {

    private final GherkinScenarioGenerator generator = new GherkinScenarioGenerator();
    
    private static final Set<String> MODAL_VERBS = Set.of(
        "should", "may", "might", "could", "would", "ideally", "possibly", "perhaps"
    );

    /**
     * Property 12: No modal verbs in Gherkin
     * For any generated Gherkin scenario, the text should not contain modal verbs 
     * (should, may, might, could, ideally)
     * 
     * Validates: Requirements 5.6
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 12: No modal verbs in Gherkin")
    void generatedScenarios_ContainNoModalVerbs(@ForAll("requirements") Requirement requirement) {
        // Generate Gherkin scenario
        GherkinScenario scenario = generator.generateScenario(requirement);
        
        // Format as string
        String formatted = generator.formatScenario(scenario);
        String lowerFormatted = formatted.toLowerCase();
        
        // Check that no modal verbs are present
        for (String modalVerb : MODAL_VERBS) {
            assertFalse(lowerFormatted.contains(" " + modalVerb + " "),
                       "Gherkin scenario should not contain modal verb '" + modalVerb + "'. Got: " + formatted);
        }
    }

    /**
     * Property 12 (variant): Modal verbs filtered from Given clauses
     * Given clauses should not contain modal verbs
     * 
     * Validates: Requirements 5.6
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 12: No modal verbs in Given clauses")
    void givenClauses_ContainNoModalVerbs(@ForAll("requirements") Requirement requirement) {
        GherkinScenario scenario = generator.generateScenario(requirement);
        
        for (String given : scenario.getGiven()) {
            String lowerGiven = given.toLowerCase();
            for (String modalVerb : MODAL_VERBS) {
                assertFalse(lowerGiven.contains(modalVerb),
                           "Given clause should not contain modal verb '" + modalVerb + "': " + given);
            }
        }
    }

    /**
     * Property 12 (variant): Modal verbs filtered from When clauses
     * When clauses should not contain modal verbs
     * 
     * Validates: Requirements 5.6
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 12: No modal verbs in When clauses")
    void whenClauses_ContainNoModalVerbs(@ForAll("requirements") Requirement requirement) {
        GherkinScenario scenario = generator.generateScenario(requirement);
        
        for (String when : scenario.getWhen()) {
            String lowerWhen = when.toLowerCase();
            for (String modalVerb : MODAL_VERBS) {
                assertFalse(lowerWhen.contains(modalVerb),
                           "When clause should not contain modal verb '" + modalVerb + "': " + when);
            }
        }
    }

    /**
     * Property 12 (variant): Modal verbs filtered from Then clauses
     * Then clauses should not contain modal verbs
     * 
     * Validates: Requirements 5.6
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 12: No modal verbs in Then clauses")
    void thenClauses_ContainNoModalVerbs(@ForAll("requirements") Requirement requirement) {
        GherkinScenario scenario = generator.generateScenario(requirement);
        
        for (String then : scenario.getThen()) {
            String lowerThen = then.toLowerCase();
            for (String modalVerb : MODAL_VERBS) {
                assertFalse(lowerThen.contains(modalVerb),
                           "Then clause should not contain modal verb '" + modalVerb + "': " + then);
            }
        }
    }

    /**
     * Property 12 (variant): Requirements with modal verbs are cleaned
     * Even when input requirements contain modal verbs, output should not
     * 
     * Validates: Requirements 5.6
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 12: Modal verbs cleaned from input")
    void requirementsWithModalVerbs_AreCleanedInOutput(@ForAll("requirementsWithModalVerbs") Requirement requirement) {
        GherkinScenario scenario = generator.generateScenario(requirement);
        String formatted = generator.formatScenario(scenario);
        String lowerFormatted = formatted.toLowerCase();
        
        // Even though input has modal verbs, output should not
        for (String modalVerb : MODAL_VERBS) {
            assertFalse(lowerFormatted.contains(" " + modalVerb + " "),
                       "Modal verb '" + modalVerb + "' should be filtered from output");
        }
    }

    /**
     * Property 12 (variant): Scenario names contain no modal verbs
     * Scenario names should also be free of modal verbs
     * 
     * Validates: Requirements 5.6
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 12: No modal verbs in scenario names")
    void scenarioNames_ContainNoModalVerbs(@ForAll("requirements") Requirement requirement) {
        GherkinScenario scenario = generator.generateScenario(requirement);
        String scenarioName = scenario.getScenario().toLowerCase();
        
        for (String modalVerb : MODAL_VERBS) {
            assertFalse(scenarioName.contains(modalVerb),
                       "Scenario name should not contain modal verb '" + modalVerb + "': " + scenario.getScenario());
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
    Arbitrary<Requirement> requirementsWithModalVerbs() {
        return Arbitraries.of(EarsPattern.values())
                .flatMap(pattern -> requirementTextsWithModalVerbs().map(text -> 
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

    @Provide
    Arbitrary<String> requirementTextsWithModalVerbs() {
        return Arbitraries.of(
                "the system should validate user input",
                "the system may process the request",
                "the system might store data in database",
                "the system could send notification to user",
                "ideally, the system logs the error",
                "the system should authenticate the user",
                "when user clicks button, the system should save data",
                "the system may display a warning message",
                "the system might optimize the query",
                "the system could retry the operation",
                "ideally, the system validates all inputs",
                "the system should possibly cache the result",
                "perhaps the system sends an email notification",
                "the system would validate credentials if possible"
        );
    }
}
