package com.pos.agent.story.analysis;

import com.pos.agent.story.models.*;
import com.pos.agent.story.models.AnalysisResult.DataRequirement;
import com.pos.agent.story.models.ParsedIssue.IssueMetadata;
import com.pos.agent.story.models.ParsedIssue.Section;
import com.pos.agent.story.models.Requirement.EarsPattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for RequirementsAnalyzer component.
 * 
 * Tests cover:
 * - Actor identification with various issue formats
 * - Intent extraction with different writing styles
 * - Ambiguity detection with known unclear scenarios
 * - Precondition and state detection
 * - Functional requirement identification
 * - Error flow detection
 * - Business rule extraction
 * - Data requirement identification
 * 
 * Requirements: 3.1, 7.1, 7.3, 8.1, 8.2, 8.3, 8.4, 8.5
 */
@DisplayName("RequirementsAnalyzer Tests")
class RequirementsAnalyzerTest {

    private RequirementsAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        analyzer = new RequirementsAnalyzer();
    }

    // ===== Actor Identification Tests =====

    @Test
    @DisplayName("Should identify actor from user story format")
    void testActorIdentification_UserStoryFormat() {
        // Given
        String body = "As a customer, I want to view my order history, so that I can track my purchases.";
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getActors()).contains("Customer");
    }

    @Test
    @DisplayName("Should identify multiple actors")
    void testActorIdentification_MultipleActors() {
        // Given
        String body = """
            As a user, I want to log in.
            As an administrator, I want to manage users.
            """;
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getActors()).containsAnyOf("User", "Administrator");
    }

    @Test
    @DisplayName("Should identify common actor roles")
    void testActorIdentification_CommonRoles() {
        // Given
        String body = "The system should allow users and admins to access the dashboard.";
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getActors()).containsAnyOf("User", "Administrator", "System");
    }

    @Test
    @DisplayName("Should provide default actor when none specified")
    void testActorIdentification_DefaultActor() {
        // Given
        String body = "The feature should work correctly.";
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getActors()).isNotEmpty();
        assertThat(result.getActors()).contains("User");
    }

    // ===== Intent Extraction Tests =====

    @Test
    @DisplayName("Should extract intent from 'I want to' pattern")
    void testIntentExtraction_IWantToPattern() {
        // Given
        String body = "As a user, I want to authenticate using OAuth, so that I can access the system securely.";
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getIntent()).contains("authenticate");
    }

    @Test
    @DisplayName("Should extract intent from description section")
    void testIntentExtraction_DescriptionSection() {
        // Given
        List<Section> sections = Arrays.asList(
            new Section("Description", "I want to implement user authentication for secure access.")
        );
        ParsedIssue issue = createParsedIssue("", sections);

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getIntent()).contains("implement user authentication");
    }

    @Test
    @DisplayName("Should extract intent from overview section")
    void testIntentExtraction_OverviewSection() {
        // Given
        List<Section> sections = Arrays.asList(
            new Section("Overview", "The system should be able to process payments efficiently.")
        );
        ParsedIssue issue = createParsedIssue("", sections);

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getIntent()).contains("process payments");
    }

    @Test
    @DisplayName("Should handle unclear intent")
    void testIntentExtraction_UnclearIntent() {
        // Given
        String body = "Fix the bug.";
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getIntent()).isNotEmpty();
    }

    // ===== Precondition Detection Tests =====

    @Test
    @DisplayName("Should detect preconditions from given statements")
    void testPreconditionDetection_GivenStatements() {
        // Given
        List<Section> sections = Arrays.asList(
            new Section("Preconditions", "Given the user is authenticated\nGiven the database is available")
        );
        ParsedIssue issue = createParsedIssue("", sections);

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getPreconditions()).isNotEmpty();
        assertThat(result.getPreconditions().get(0).getPattern()).isEqualTo(EarsPattern.STATE_DRIVEN);
    }

    @Test
    @DisplayName("Should detect state-related preconditions")
    void testPreconditionDetection_StateKeywords() {
        // Given
        String body = "When the system is in maintenance mode, users cannot log in.";
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getPreconditions()).isNotEmpty();
    }

    // ===== Functional Requirements Tests =====

    @Test
    @DisplayName("Should identify functional requirements from requirements section")
    void testFunctionalRequirements_RequirementsSection() {
        // Given
        List<Section> sections = Arrays.asList(
            new Section("Requirements", """
                - User can log in with username and password
                - System validates credentials
                - Session is created upon successful login
                """)
        );
        ParsedIssue issue = createParsedIssue("", sections);

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getFunctionalRequirements()).hasSizeGreaterThan(0);
    }

    @Test
    @DisplayName("Should identify functional requirements from acceptance criteria")
    void testFunctionalRequirements_AcceptanceCriteria() {
        // Given
        List<Section> sections = Arrays.asList(
            new Section("Acceptance Criteria", """
                - User enters valid credentials
                - System authenticates user
                - Dashboard is displayed
                """)
        );
        ParsedIssue issue = createParsedIssue("", sections);

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getFunctionalRequirements()).hasSizeGreaterThan(0);
    }

    // ===== Error Flow Detection Tests =====

    @Test
    @DisplayName("Should detect error flows from error section")
    void testErrorFlowDetection_ErrorSection() {
        // Given
        List<Section> sections = Arrays.asList(
            new Section("Error Handling", """
                - Invalid credentials are rejected
                - System displays error message
                - Failed login attempts are logged
                """)
        );
        ParsedIssue issue = createParsedIssue("", sections);

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getErrorFlows()).isNotEmpty();
        assertThat(result.getErrorFlows().get(0).getPattern()).isEqualTo(EarsPattern.UNWANTED);
    }

    @Test
    @DisplayName("Should detect error flows from error keywords")
    void testErrorFlowDetection_ErrorKeywords() {
        // Given
        String body = "If authentication fails, the system should reject the request. Timeout errors should be handled gracefully.";
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getErrorFlows()).isNotEmpty();
    }

    // ===== Business Rules Tests =====

    @Test
    @DisplayName("Should extract business rules from rules section")
    void testBusinessRules_RulesSection() {
        // Given
        List<Section> sections = Arrays.asList(
            new Section("Business Rules", """
                - Passwords must be at least 8 characters
                - Users must be over 18 years old
                - Email addresses must be unique
                """)
        );
        ParsedIssue issue = createParsedIssue("", sections);

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getBusinessRules()).hasSizeGreaterThan(0);
        assertThat(result.getBusinessRules().get(0).getPattern()).isEqualTo(EarsPattern.UBIQUITOUS);
    }

    // ===== Data Requirements Tests =====

    @Test
    @DisplayName("Should identify data requirements from data section")
    void testDataRequirements_DataSection() {
        // Given
        List<Section> sections = Arrays.asList(
            new Section("Data Model", """
                username: User's login name (required)
                password: User's password (required)
                email: User's email address
                """)
        );
        ParsedIssue issue = createParsedIssue("", sections);

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getDataRequirements()).hasSizeGreaterThan(0);
        DataRequirement firstField = result.getDataRequirements().get(0);
        assertThat(firstField.getFieldName()).isNotEmpty();
    }

    @Test
    @DisplayName("Should detect required vs optional fields")
    void testDataRequirements_RequiredFields() {
        // Given
        List<Section> sections = Arrays.asList(
            new Section("Fields", """
                username: required field
                nickname: optional field
                """)
        );
        ParsedIssue issue = createParsedIssue("", sections);

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getDataRequirements()).isNotEmpty();
        boolean hasRequiredField = result.getDataRequirements().stream()
            .anyMatch(DataRequirement::isRequired);
        assertThat(hasRequiredField).isTrue();
    }

    // ===== Ambiguity Detection Tests =====

    @Test
    @DisplayName("Should flag vague terms as ambiguities")
    void testAmbiguityDetection_VagueTerms() {
        // Given
        String body = "The system should respond quickly and be user-friendly.";
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getAmbiguities()).isNotEmpty();
        assertThat(result.getAmbiguities().get(0).getQuestion()).containsIgnoringCase("criteria");
    }

    @Test
    @DisplayName("Should flag unclear intent as ambiguity")
    void testAmbiguityDetection_UnclearIntent() {
        // Given
        String body = "Fix it.";
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getAmbiguities()).isNotEmpty();
        boolean hasIntentQuestion = result.getAmbiguities().stream()
            .anyMatch(q -> q.getQuestion().toLowerCase().contains("intent"));
        assertThat(hasIntentQuestion).isTrue();
    }

    @Test
    @DisplayName("Should flag missing data specifications")
    void testAmbiguityDetection_MissingDataSpecs() {
        // Given
        String body = "As a user, I want to create an account.";
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getAmbiguities()).isNotEmpty();
        boolean hasDataQuestion = result.getAmbiguities().stream()
            .anyMatch(q -> q.getQuestion().toLowerCase().contains("data"));
        assertThat(hasDataQuestion).isTrue();
    }

    @Test
    @DisplayName("Should flag missing error handling")
    void testAmbiguityDetection_MissingErrorHandling() {
        // Given
        String body = "As a user, I want to log in with my credentials.";
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getAmbiguities()).isNotEmpty();
        boolean hasErrorQuestion = result.getAmbiguities().stream()
            .anyMatch(q -> q.getQuestion().toLowerCase().contains("error"));
        assertThat(hasErrorQuestion).isTrue();
    }

    // ===== Complex Scenario Tests =====

    @Test
    @DisplayName("Should analyze complete user story with all elements")
    void testCompleteAnalysis_FullUserStory() {
        // Given
        List<Section> sections = Arrays.asList(
            new Section("Description", "As a customer, I want to place an order for products."),
            new Section("Acceptance Criteria", """
                - Customer can add items to cart
                - Customer can review order before submission
                - System validates inventory availability
                """),
            new Section("Data Requirements", """
                orderId: Unique order identifier (required)
                customerId: Customer reference (required)
                items: List of order items
                """),
            new Section("Error Handling", """
                - Out of stock items are rejected
                - Invalid payment methods are rejected
                """)
        );
        ParsedIssue issue = createParsedIssue("", sections);

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getIntent()).contains("place an order");
        assertThat(result.getActors()).contains("Customer");
        assertThat(result.getFunctionalRequirements()).isNotEmpty();
        assertThat(result.getDataRequirements()).hasSizeGreaterThan(0);
        assertThat(result.getErrorFlows()).isNotEmpty();
    }

    @Test
    @DisplayName("Should handle minimal issue with defaults")
    void testCompleteAnalysis_MinimalIssue() {
        // Given
        String body = "Add login feature.";
        ParsedIssue issue = createParsedIssue(body, Collections.emptyList());

        // When
        AnalysisResult result = analyzer.analyzeRequirements(issue);

        // Then
        assertThat(result.getIntent()).isNotEmpty();
        assertThat(result.getActors()).isNotEmpty();
        assertThat(result.getAmbiguities()).isNotEmpty(); // Should flag many ambiguities
    }

    // ===== Error Handling Tests =====

    @Test
    @DisplayName("Should throw exception for null parsed issue")
    void testErrorHandling_NullIssue() {
        // When/Then
        assertThatThrownBy(() -> analyzer.analyzeRequirements(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("Parsed issue cannot be null");
    }

    // ===== Helper Methods =====

    private ParsedIssue createParsedIssue(String body, List<Section> sections) {
        IssueMetadata metadata = new IssueMetadata(
            "Test Issue",
            Collections.emptyList(),
            "test-repo"
        );
        return new ParsedIssue(metadata, body, sections);
    }
}
