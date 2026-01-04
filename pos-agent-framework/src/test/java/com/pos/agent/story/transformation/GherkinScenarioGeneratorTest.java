package com.pos.agent.story.transformation;

import com.pos.agent.story.models.GherkinScenario;
import com.pos.agent.story.models.Requirement;
import com.pos.agent.story.models.Requirement.EarsPattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GherkinScenarioGenerator.
 * Tests Given/When/Then structure, compound condition detection, and modal verb filtering.
 * 
 * Requirements: 5.1, 5.4, 5.6
 */
class GherkinScenarioGeneratorTest {

    private GherkinScenarioGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new GherkinScenarioGenerator();
    }

    // ========== Given/When/Then Structure Tests (5.1) ==========

    @Test
    void testGenerateScenario_BasicStructure() {
        Requirement req = new Requirement(
            "When user submits form, the system validates input",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        assertNotNull(scenario);
        assertNotNull(scenario.getScenario());
        assertNotNull(scenario.getGiven());
        assertNotNull(scenario.getWhen());
        assertNotNull(scenario.getThen());
    }

    @Test
    void testGenerateScenario_ExtractsWhenClause() {
        Requirement req = new Requirement(
            "When user clicks submit button, the system processes the request",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        assertFalse(scenario.getWhen().isEmpty());
        assertTrue(scenario.getWhen().get(0).toLowerCase().contains("user clicks"));
    }

    @Test
    void testGenerateScenario_ExtractsThenClause() {
        Requirement req = new Requirement(
            "When user submits, then the system validates the data",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        assertFalse(scenario.getThen().isEmpty());
        assertTrue(scenario.getThen().get(0).toLowerCase().contains("validates"));
    }

    @Test
    void testGenerateScenario_ExtractsGivenClause() {
        Requirement req = new Requirement(
            "Given the user is authenticated, when user accesses dashboard, the system displays data",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        assertFalse(scenario.getGiven().isEmpty());
        assertTrue(scenario.getGiven().get(0).toLowerCase().contains("authenticated"));
    }

    @Test
    void testGenerateScenario_CompleteGivenWhenThen() {
        Requirement req = new Requirement(
            "Given user is logged in, when user clicks logout, then the system ends the session",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        assertFalse(scenario.getGiven().isEmpty(), "Should have Given clause");
        assertFalse(scenario.getWhen().isEmpty(), "Should have When clause");
        assertFalse(scenario.getThen().isEmpty(), "Should have Then clause");
    }

    // ========== Compound Condition Tests (5.4) ==========

    @Test
    void testSplitCompoundConditions_AndInGiven() {
        Requirement req = new Requirement(
            "Given user is authenticated and user has permissions, when user accesses resource, then system allows access",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        // Should split "authenticated and has permissions" into two Given clauses
        assertTrue(scenario.getGiven().size() >= 2, 
                  "Compound Given should be split. Got: " + scenario.getGiven());
    }

    @Test
    void testSplitCompoundConditions_AndInWhen() {
        Requirement req = new Requirement(
            "When user enters username and user enters password, then system authenticates",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        // Should split "enters username and enters password" into two When clauses
        assertTrue(scenario.getWhen().size() >= 2,
                  "Compound When should be split. Got: " + scenario.getWhen());
    }

    @Test
    void testSplitCompoundConditions_AndInThen() {
        Requirement req = new Requirement(
            "When user submits, then system validates data and system stores result",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        // Should split "validates and stores" into two Then clauses
        assertTrue(scenario.getThen().size() >= 2,
                  "Compound Then should be split. Got: " + scenario.getThen());
    }

    @Test
    void testSplitCompoundConditions_DoesNotSplitAndThen() {
        Requirement req = new Requirement(
            "When user submits and then system validates, then result is stored",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        // "and then" should not be split
        String formatted = generator.formatScenario(scenario);
        assertFalse(formatted.contains("And then"), 
                   "Should not split 'and then' as separate clause");
    }

    // ========== Modal Verb Filtering Tests (5.6) ==========

    @Test
    void testFilterModalVerbs_Should() {
        Requirement req = new Requirement(
            "When user submits, the system should validate the input",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        String formatted = generator.formatScenario(scenario);
        assertFalse(formatted.toLowerCase().contains("should"),
                   "Modal verb 'should' should be filtered");
    }

    @Test
    void testFilterModalVerbs_May() {
        Requirement req = new Requirement(
            "When user requests, the system may return cached data",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        String formatted = generator.formatScenario(scenario);
        assertFalse(formatted.toLowerCase().contains(" may "),
                   "Modal verb 'may' should be filtered");
    }

    @Test
    void testFilterModalVerbs_Might() {
        Requirement req = new Requirement(
            "The system might display a warning message",
            EarsPattern.UBIQUITOUS,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        String formatted = generator.formatScenario(scenario);
        assertFalse(formatted.toLowerCase().contains("might"),
                   "Modal verb 'might' should be filtered");
    }

    @Test
    void testFilterModalVerbs_Could() {
        Requirement req = new Requirement(
            "The system could optimize the query",
            EarsPattern.UBIQUITOUS,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        String formatted = generator.formatScenario(scenario);
        assertFalse(formatted.toLowerCase().contains("could"),
                   "Modal verb 'could' should be filtered");
    }

    @Test
    void testFilterModalVerbs_Ideally() {
        Requirement req = new Requirement(
            "Ideally, the system validates all inputs",
            EarsPattern.UBIQUITOUS,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        String formatted = generator.formatScenario(scenario);
        assertFalse(formatted.toLowerCase().contains("ideally"),
                   "Modal verb 'ideally' should be filtered");
    }

    @Test
    void testFilterModalVerbs_MultipleModalVerbs() {
        Requirement req = new Requirement(
            "The system should ideally validate input and might display warnings",
            EarsPattern.UBIQUITOUS,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        String formatted = generator.formatScenario(scenario);
        assertFalse(formatted.toLowerCase().contains("should"),
                   "Modal verb 'should' should be filtered");
        assertFalse(formatted.toLowerCase().contains("ideally"),
                   "Modal verb 'ideally' should be filtered");
        assertFalse(formatted.toLowerCase().contains("might"),
                   "Modal verb 'might' should be filtered");
    }

    // ========== Scenario Formatting Tests (5.1, 5.5) ==========

    @Test
    void testFormatScenario_ProperStructure() {
        GherkinScenario scenario = new GherkinScenario(
            "User logs in successfully",
            Arrays.asList("the user is on the login page"),
            Arrays.asList("the user enters valid credentials", "the user clicks login"),
            Arrays.asList("the system authenticates the user", "the dashboard is displayed")
        );
        
        String formatted = generator.formatScenario(scenario);
        
        assertTrue(formatted.contains("Scenario:"));
        assertTrue(formatted.contains("Given"));
        assertTrue(formatted.contains("When"));
        assertTrue(formatted.contains("Then"));
        assertTrue(formatted.contains("And"));
    }

    @Test
    void testFormatScenario_UsesAndForMultipleClauses() {
        GherkinScenario scenario = new GherkinScenario(
            "Test scenario",
            Arrays.asList("condition 1", "condition 2"),
            Arrays.asList("action 1", "action 2"),
            Arrays.asList("result 1", "result 2")
        );
        
        String formatted = generator.formatScenario(scenario);
        
        // Count "And" occurrences (should be 4: 1 for Given, 1 for When, 1 for Then, plus extras)
        int andCount = formatted.split("\\bAnd\\b").length - 1;
        assertTrue(andCount >= 3, "Should use 'And' for multiple clauses in each section");
    }

    @Test
    void testFormatScenario_NoProse() {
        GherkinScenario scenario = new GherkinScenario(
            "Simple test",
            Arrays.asList("precondition"),
            Arrays.asList("action"),
            Arrays.asList("result")
        );
        
        String formatted = generator.formatScenario(scenario);
        
        // Should only contain Gherkin keywords and clauses, no free-form prose
        String[] lines = formatted.split("\n");
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                assertTrue(
                    line.trim().startsWith("Scenario:") ||
                    line.trim().startsWith("Given") ||
                    line.trim().startsWith("When") ||
                    line.trim().startsWith("Then") ||
                    line.trim().startsWith("And"),
                    "Line should start with Gherkin keyword: " + line
                );
            }
        }
    }

    // ========== Multiple Scenarios Tests ==========

    @Test
    void testGenerateScenarios_MultipleRequirements() {
        List<Requirement> requirements = Arrays.asList(
            new Requirement("When user logs in, system authenticates", EarsPattern.EVENT_DRIVEN, true),
            new Requirement("When user logs out, system ends session", EarsPattern.EVENT_DRIVEN, true),
            new Requirement("The system validates all inputs", EarsPattern.UBIQUITOUS, true)
        );
        
        List<GherkinScenario> scenarios = generator.generateScenarios(requirements);
        
        assertEquals(3, scenarios.size());
        for (GherkinScenario scenario : scenarios) {
            assertNotNull(scenario.getScenario());
        }
    }

    @Test
    void testGenerateScenarios_EmptyList() {
        List<Requirement> requirements = List.of();
        
        List<GherkinScenario> scenarios = generator.generateScenarios(requirements);
        
        assertTrue(scenarios.isEmpty());
    }

    // ========== Edge Cases ==========

    @Test
    void testGenerateScenario_NullRequirement() {
        assertThrows(IllegalArgumentException.class, () -> {
            generator.generateScenario(null);
        });
    }

    @Test
    void testGenerateScenarios_NullList() {
        assertThrows(IllegalArgumentException.class, () -> {
            generator.generateScenarios(null);
        });
    }

    @Test
    void testFormatScenario_NullScenario() {
        assertThrows(IllegalArgumentException.class, () -> {
            generator.formatScenario(null);
        });
    }

    @Test
    void testGenerateScenario_EmptyText() {
        Requirement req = new Requirement("", EarsPattern.UBIQUITOUS, true);
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        assertNotNull(scenario);
        assertNotNull(scenario.getScenario());
    }

    @Test
    void testGenerateScenario_VeryLongText() {
        String longText = "When user performs action ".repeat(50) + "then system responds";
        Requirement req = new Requirement(longText, EarsPattern.EVENT_DRIVEN, true);
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        assertNotNull(scenario);
        // Scenario name should be truncated
        assertTrue(scenario.getScenario().length() <= 80);
    }

    // ========== Verifiable Then Clauses Tests (5.2) ==========

    @Test
    void testVerifiableThenClauses_ConcreteOutcome() {
        Requirement req = new Requirement(
            "When user submits, then the system validates and stores the data",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        assertFalse(scenario.getThen().isEmpty());
        // Then clauses should describe concrete, testable outcomes
        for (String thenClause : scenario.getThen()) {
            assertFalse(thenClause.trim().isEmpty(), "Then clause should not be empty");
        }
    }

    @Test
    void testVerifiableThenClauses_NoVagueTerms() {
        Requirement req = new Requirement(
            "When user submits, the system processes correctly",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        GherkinScenario scenario = generator.generateScenario(req);
        
        // Even with vague term "correctly", should still generate Then clause
        assertFalse(scenario.getThen().isEmpty());
    }
}
