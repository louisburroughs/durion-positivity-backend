package com.pos.agent.story.transformation;

import com.pos.agent.story.models.Requirement;
import com.pos.agent.story.models.Requirement.EarsPattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for EarsPatternTransformer.
 * Tests all EARS pattern transformations and edge cases.
 * 
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5
 */
class EarsPatternTransformerTest {

    private EarsPatternTransformer transformer;

    @BeforeEach
    void setUp() {
        transformer = new EarsPatternTransformer();
    }

    // ========== Ubiquitous Pattern Tests (6.1, 6.5) ==========

    @Test
    void testUbiquitousPattern_SimpleRequirement() {
        Requirement req = new Requirement("validate user input", EarsPattern.UBIQUITOUS, true);
        
        String result = transformer.transformRequirement(req);
        
        assertEquals("THE the system SHALL validate user input", result);
        assertTrue(result.contains("the system SHALL"));
    }

    @Test
    void testUbiquitousPattern_WithMustPrefix() {
        Requirement req = new Requirement("must validate user input", EarsPattern.UBIQUITOUS, true);
        
        String result = transformer.transformRequirement(req);
        
        assertEquals("THE the system SHALL validate user input", result);
        assertFalse(result.contains("must"));
    }

    @Test
    void testUbiquitousPattern_WithShouldPrefix() {
        Requirement req = new Requirement("should validate user input", EarsPattern.UBIQUITOUS, true);
        
        String result = transformer.transformRequirement(req);
        
        assertEquals("THE the system SHALL validate user input", result);
        assertFalse(result.contains("should"));
    }

    @Test
    void testUbiquitousPattern_AlreadyFormatted() {
        Requirement req = new Requirement("THE system SHALL validate user input", EarsPattern.UBIQUITOUS, true);
        
        String result = transformer.transformRequirement(req);
        
        assertEquals("THE the system SHALL validate user input", result);
    }

    @Test
    void testUbiquitousPattern_WithAlwaysPrefix() {
        Requirement req = new Requirement("always validate user input", EarsPattern.UBIQUITOUS, true);
        
        String result = transformer.transformRequirement(req);
        
        assertEquals("THE the system SHALL validate user input", result);
        assertFalse(result.contains("always"));
    }

    // ========== State-Driven Pattern Tests (6.2, 6.5) ==========

    @Test
    void testStateDrivenPattern_SimpleState() {
        Requirement req = new Requirement("while processing, validate data", EarsPattern.STATE_DRIVEN, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("WHILE"));
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("processing"));
        assertTrue(result.contains("validate data"));
    }

    @Test
    void testStateDrivenPattern_InStateFormat() {
        Requirement req = new Requirement("in active state, process requests", EarsPattern.STATE_DRIVEN, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("WHILE"));
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("active state"));
    }

    @Test
    void testStateDrivenPattern_DuringStateFormat() {
        Requirement req = new Requirement("during initialization state, load configuration", EarsPattern.STATE_DRIVEN, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("WHILE"));
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("initialization state"));
    }

    @Test
    void testStateDrivenPattern_NoExplicitState() {
        Requirement req = new Requirement("validate preconditions", EarsPattern.STATE_DRIVEN, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("WHILE"));
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("in the appropriate state"));
    }

    @Test
    void testStateDrivenPattern_AlreadyFormatted() {
        Requirement req = new Requirement("WHILE in ready state THEN the system SHALL process", EarsPattern.STATE_DRIVEN, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("WHILE"));
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("ready state"));
    }

    // ========== Event-Driven Pattern Tests (6.3, 6.5) ==========

    @Test
    void testEventDrivenPattern_WhenFormat() {
        Requirement req = new Requirement("when user submits form, validate input", EarsPattern.EVENT_DRIVEN, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("WHEN"));
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("user submits form"));
        assertTrue(result.contains("validate input"));
    }

    @Test
    void testEventDrivenPattern_OnFormat() {
        Requirement req = new Requirement("on button click, save data", EarsPattern.EVENT_DRIVEN, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("WHEN"));
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("button click"));
    }

    @Test
    void testEventDrivenPattern_UponFormat() {
        Requirement req = new Requirement("upon receiving request, process data", EarsPattern.EVENT_DRIVEN, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("WHEN"));
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("receiving request"));
    }

    @Test
    void testEventDrivenPattern_AfterFormat() {
        Requirement req = new Requirement("after validation, store result", EarsPattern.EVENT_DRIVEN, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("WHEN"));
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("validation"));
    }

    @Test
    void testEventDrivenPattern_NoExplicitTrigger() {
        Requirement req = new Requirement("process the request", EarsPattern.EVENT_DRIVEN, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("WHEN"));
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("the event occurs"));
    }

    @Test
    void testEventDrivenPattern_AlreadyFormatted() {
        Requirement req = new Requirement("WHEN user clicks button THEN process action", EarsPattern.EVENT_DRIVEN, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("WHEN"));
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("user clicks button"));
    }

    // ========== Unwanted Behavior Pattern Tests (6.4, 6.5) ==========

    @Test
    void testUnwantedPattern_IfFormat() {
        Requirement req = new Requirement("if validation fails, reject request", EarsPattern.UNWANTED, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("IF"));
        assertTrue(result.contains("THEN THE the system SHALL"));
        assertTrue(result.contains("validation fails"));
        assertTrue(result.contains("reject request"));
    }

    @Test
    void testUnwantedPattern_InCaseOfFormat() {
        Requirement req = new Requirement("in case of error, log details", EarsPattern.UNWANTED, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("IF"));
        assertTrue(result.contains("THEN THE the system SHALL"));
        assertTrue(result.contains("error"));
    }

    @Test
    void testUnwantedPattern_NoExplicitCondition() {
        Requirement req = new Requirement("handle the error", EarsPattern.UNWANTED, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("IF"));
        assertTrue(result.contains("THEN THE the system SHALL"));
        assertTrue(result.contains("an error occurs"));
    }

    @Test
    void testUnwantedPattern_AlreadyFormatted() {
        Requirement req = new Requirement("IF timeout occurs THEN reject request", EarsPattern.UNWANTED, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("IF"));
        assertTrue(result.contains("THEN THE the system SHALL"));
        assertTrue(result.contains("timeout occurs"));
    }

    // ========== List Transformation Tests ==========

    @Test
    void testTransformRequirements_MultipleRequirements() {
        List<Requirement> requirements = Arrays.asList(
            new Requirement("validate input", EarsPattern.UBIQUITOUS, true),
            new Requirement("when user submits, process data", EarsPattern.EVENT_DRIVEN, true),
            new Requirement("if error occurs, log it", EarsPattern.UNWANTED, true)
        );
        
        List<String> results = transformer.transformRequirements(requirements);
        
        assertEquals(3, results.size());
        assertTrue(results.get(0).contains("THE the system SHALL"));
        assertTrue(results.get(1).startsWith("WHEN"));
        assertTrue(results.get(2).startsWith("IF"));
    }

    @Test
    void testTransformRequirements_EmptyList() {
        List<Requirement> requirements = List.of();
        
        List<String> results = transformer.transformRequirements(requirements);
        
        assertTrue(results.isEmpty());
    }

    // ========== Edge Cases and Error Handling ==========

    @Test
    void testTransformRequirement_NullRequirement() {
        assertThrows(IllegalArgumentException.class, () -> {
            transformer.transformRequirement(null);
        });
    }

    @Test
    void testTransformRequirements_NullList() {
        assertThrows(IllegalArgumentException.class, () -> {
            transformer.transformRequirements(null);
        });
    }

    @Test
    void testTransformRequirement_EmptyText() {
        Requirement req = new Requirement("", EarsPattern.UBIQUITOUS, true);
        
        String result = transformer.transformRequirement(req);
        
        assertNotNull(result);
        assertTrue(result.contains("the system SHALL"));
    }

    @Test
    void testTransformRequirement_WhitespaceText() {
        Requirement req = new Requirement("   ", EarsPattern.UBIQUITOUS, true);
        
        String result = transformer.transformRequirement(req);
        
        assertNotNull(result);
        assertTrue(result.contains("the system SHALL"));
    }

    // ========== Consistency Tests (6.5) ==========

    @Test
    void testAllPatterns_ContainSystemShall() {
        List<Requirement> requirements = Arrays.asList(
            new Requirement("validate input", EarsPattern.UBIQUITOUS, true),
            new Requirement("while active, process", EarsPattern.STATE_DRIVEN, true),
            new Requirement("when triggered, execute", EarsPattern.EVENT_DRIVEN, true),
            new Requirement("if error, handle", EarsPattern.UNWANTED, true)
        );
        
        for (Requirement req : requirements) {
            String result = transformer.transformRequirement(req);
            assertTrue(result.contains("the system SHALL"), 
                      "Pattern " + req.getPattern() + " should contain 'the system SHALL'");
        }
    }

    @Test
    void testSystemShallPhrasing_ConsistentCasing() {
        Requirement req = new Requirement("THE SYSTEM SHALL validate", EarsPattern.UBIQUITOUS, true);
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.contains("the system SHALL"));
        assertFalse(result.contains("THE SYSTEM SHALL"));
    }

    // ========== Complex Scenarios ==========

    @Test
    void testComplexRequirement_MultipleConditions() {
        Requirement req = new Requirement(
            "when user submits form and validation passes, store data and send confirmation",
            EarsPattern.EVENT_DRIVEN,
            true
        );
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("WHEN"));
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("user submits form"));
    }

    @Test
    void testComplexRequirement_NestedConditions() {
        Requirement req = new Requirement(
            "if validation fails and user is authenticated, show detailed error message",
            EarsPattern.UNWANTED,
            true
        );
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.startsWith("IF"));
        assertTrue(result.contains("THEN THE the system SHALL"));
        assertTrue(result.contains("validation fails"));
    }

    @Test
    void testRequirement_WithSpecialCharacters() {
        Requirement req = new Requirement(
            "validate user's input (including special chars: @, #, $)",
            EarsPattern.UBIQUITOUS,
            true
        );
        
        String result = transformer.transformRequirement(req);
        
        assertTrue(result.contains("the system SHALL"));
        assertTrue(result.contains("@"));
        assertTrue(result.contains("#"));
        assertTrue(result.contains("$"));
    }
}
