package com.pos.agent.story.analysis;

import net.jqwik.api.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test for acceptance criteria presence validation.
 * Validates that the QualityStandardsValidator correctly identifies requirements
 * that are missing acceptance criteria.
 * 
 * Feature: upgrade-story-quality, Property 14: Acceptance criteria presence
 * Validates: Requirements 7.2
 */
class AcceptanceCriteriaPresencePropertyTest {

    private final QualityStandardsValidator validator = new QualityStandardsValidator();

    /**
     * Property 14: Acceptance criteria presence
     * For any requirements text that contains requirements but no acceptance criteria,
     * the validator should flag it as missing acceptance criteria.
     * 
     * Validates: Requirements 7.2
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 14: Acceptance criteria presence")
    void requirementsWithoutAcceptanceCriteria_AreFlagged(
            @ForAll("requirementsWithoutCriteria") String requirementsText) {
        
        List<String> missing = validator.checkAcceptanceCriteriaPresence(requirementsText);
        
        // Should detect missing acceptance criteria
        assertFalse(missing.isEmpty(),
                   "Should flag requirements without acceptance criteria");
        assertTrue(missing.stream().anyMatch(m -> 
                   m.contains("no Acceptance Criteria") || m.contains("no Gherkin")),
                   "Should indicate missing acceptance criteria or Gherkin scenarios");
    }

    /**
     * Property 14 (variant): Requirements with acceptance criteria pass validation
     * For any requirements text that contains both requirements and acceptance criteria,
     * the validator should not flag it as missing.
     * 
     * Validates: Requirements 7.2
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 14: Complete requirements pass")
    void requirementsWithAcceptanceCriteria_PassValidation(
            @ForAll("requirementsWithCriteria") String requirementsText) {
        
        List<String> missing = validator.checkAcceptanceCriteriaPresence(requirementsText);
        
        // Should not flag as missing
        assertTrue(missing.isEmpty(),
                  "Should not flag requirements that have acceptance criteria. Got: " + missing);
    }

    /**
     * Property 14 (variant): EARS without Gherkin is flagged
     * For any text with EARS requirements but no Gherkin scenarios,
     * the validator should flag the missing Gherkin acceptance criteria.
     * 
     * Validates: Requirements 7.2
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 14: EARS without Gherkin flagged")
    void earsWithoutGherkin_IsFlagged(@ForAll("earsRequirementsOnly") String requirementsText) {
        
        List<String> missing = validator.checkAcceptanceCriteriaPresence(requirementsText);
        
        // Should detect missing Gherkin scenarios or acceptance criteria
        assertFalse(missing.isEmpty(),
                   "Should flag EARS requirements without Gherkin scenarios. Got: " + missing);
        assertTrue(missing.stream().anyMatch(m -> 
                   (m.contains("EARS") && m.contains("Gherkin")) || 
                   m.contains("no Acceptance Criteria")),
                   "Should indicate EARS requirements need Gherkin scenarios or missing acceptance criteria. Got: " + missing);
    }

    /**
     * Property 14 (variant): Empty text passes validation
     * Empty or null text should not be flagged as missing acceptance criteria.
     * 
     * Validates: Requirements 7.2
     */
    @Property(tries = 50)
    @Label("Feature: upgrade-story-quality, Property 14: Empty text passes")
    void emptyText_PassesValidation(@ForAll("emptyOrWhitespace") String text) {
        
        List<String> missing = validator.checkAcceptanceCriteriaPresence(text);
        
        // Empty text should not be flagged
        assertTrue(missing.isEmpty(),
                  "Empty text should not be flagged as missing criteria");
    }

    /**
     * Property 14 (variant): Multiple requirements sections detected
     * Text with multiple requirement sections should still be validated correctly.
     * 
     * Validates: Requirements 7.2
     */
    @Property(tries = 50)
    @Label("Feature: upgrade-story-quality, Property 14: Multiple sections handled")
    void multipleRequirementSections_AreValidatedCorrectly(
            @ForAll("multipleRequirementSections") String requirementsText) {
        
        List<String> missing = validator.checkAcceptanceCriteriaPresence(requirementsText);
        
        // Should detect if any section is missing acceptance criteria
        assertFalse(missing.isEmpty(),
                   "Should flag multiple requirement sections without acceptance criteria");
    }

    // ========== Generators ==========

    @Provide
    Arbitrary<String> requirementsWithoutCriteria() {
        return Arbitraries.of(
                """
                ## Requirements
                THE system SHALL validate input
                THE system SHALL store data
                """,
                """
                ## Functional Requirements
                WHEN user submits form THEN THE system SHALL validate
                WHEN data is stored THEN THE system SHALL log
                """,
                """
                ## Business Rules
                THE system SHALL enforce permissions
                THE system SHALL audit all changes
                """,
                """
                ## Requirements
                THE system SHALL process requests within 500ms
                THE system SHALL handle errors gracefully
                THE system SHALL log all operations
                """,
                """
                ## System Requirements
                WHILE processing THEN THE system SHALL validate
                IF error occurs THEN THE system SHALL log
                """
        );
    }

    @Provide
    Arbitrary<String> requirementsWithCriteria() {
        return Arbitraries.of(
                """
                ## Requirements
                THE system SHALL validate input
                
                ## Acceptance Criteria
                Given valid input
                When user submits
                Then system accepts
                """,
                """
                ## Functional Requirements
                WHEN user submits form THEN THE system SHALL validate
                
                ## Acceptance Criteria
                Given a form with valid data
                When the user clicks submit
                Then the system validates all fields
                """,
                """
                ## Requirements
                THE system SHALL process requests
                
                ## Acceptance Criterion
                Given a valid request
                When processing begins
                Then the system completes successfully
                """,
                """
                ## Business Rules
                THE system SHALL enforce permissions
                
                ## Acceptance Criteria
                Given a user with permissions
                When accessing resource
                Then system grants access
                """,
                """
                THE system SHALL validate input
                
                Given valid input
                When validation runs
                Then system accepts
                """
        );
    }

    @Provide
    Arbitrary<String> earsRequirementsOnly() {
        return Arbitraries.of(
                """
                ## Requirements
                THE system SHALL validate input
                THE system SHALL store data
                THE system SHALL notify user
                """,
                """
                ## Functional Requirements
                WHEN user submits THEN THE system SHALL validate
                WHEN data is stored THEN THE system SHALL log
                WHEN error occurs THEN THE system SHALL notify
                """,
                """
                ## Business Rules
                WHILE processing THEN THE system SHALL validate
                IF error occurs THEN THE system SHALL log
                THE system SHALL authenticate user
                """,
                """
                ## System Requirements
                THE system SHALL process requests
                WHEN timeout occurs THEN THE system SHALL retry
                IF validation fails THEN THE system SHALL reject
                """
        );
    }

    @Provide
    Arbitrary<String> emptyOrWhitespace() {
        return Arbitraries.of(
                "",
                "   ",
                "\n",
                "\t",
                "  \n  \t  "
        );
    }

    @Provide
    Arbitrary<String> multipleRequirementSections() {
        return Arbitraries.of(
                """
                ## Requirements
                THE system SHALL validate input
                
                ## Functional Requirements
                THE system SHALL store data
                
                ## Business Rules
                THE system SHALL enforce permissions
                """,
                """
                ## System Requirements
                WHEN user submits THEN THE system SHALL validate
                
                ## Business Requirements
                THE system SHALL audit changes
                
                ## Technical Requirements
                THE system SHALL log errors
                """
        );
    }
}
