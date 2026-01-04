package com.pos.agent.story.parsing;

import com.pos.agent.story.models.GitHubIssue;
import com.pos.agent.story.models.ParsedIssue;
import com.pos.agent.story.models.ParsedIssue.IssueMetadata;
import com.pos.agent.story.models.ParsedIssue.Section;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for IssueParser component.
 * 
 * Tests cover:
 * - Metadata extraction with various issue formats
 * - Markdown parsing with different structures
 * - Section identification and content extraction
 * - Error handling for invalid inputs
 * 
 * Requirements: 2.1, 2.2, 2.3, 2.4
 */
@DisplayName("IssueParser Tests")
class IssueParserTest {

    private IssueParser parser;

    @BeforeEach
    void setUp() {
        parser = new IssueParser();
    }

    // ===== Metadata Extraction Tests =====

    @Test
    @DisplayName("Should extract all metadata fields correctly")
    void testMetadataExtraction_AllFields() throws IssueParser.IssueParserException {
        // Given
        GitHubIssue issue = createIssue(
            "[BACKEND] [STORY] User Authentication",
            "Issue body content",
            Arrays.asList("backend", "story", "authentication"),
            "durion-positivity-backend",
            123
        );

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        IssueMetadata metadata = result.getMetadata();
        assertThat(metadata.getTitle()).isEqualTo("[BACKEND] [STORY] User Authentication");
        assertThat(metadata.getLabels()).containsExactly("backend", "story", "authentication");
        assertThat(metadata.getRepository()).isEqualTo("durion-positivity-backend");
    }

    @Test
    @DisplayName("Should handle issue with no labels")
    void testMetadataExtraction_NoLabels() throws IssueParser.IssueParserException {
        // Given
        GitHubIssue issue = createIssue(
            "Simple Issue",
            "Body content",
            Collections.emptyList(),
            "test-repo",
            1
        );

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getMetadata().getLabels()).isEmpty();
    }

    @Test
    @DisplayName("Should handle issue with special characters in title")
    void testMetadataExtraction_SpecialCharacters() throws IssueParser.IssueParserException {
        // Given
        GitHubIssue issue = createIssue(
            "Issue with <special> & \"characters\" [test]",
            "Body",
            Collections.emptyList(),
            "repo",
            1
        );

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getMetadata().getTitle())
            .isEqualTo("Issue with <special> & \"characters\" [test]");
    }

    // ===== Markdown Parsing Tests =====

    @Test
    @DisplayName("Should parse simple markdown with single heading")
    void testMarkdownParsing_SingleHeading() throws IssueParser.IssueParserException {
        // Given
        String body = """
            # Overview
            
            This is the overview section with some content.
            """;
        GitHubIssue issue = createIssue("Test", body, Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getSections()).hasSize(1);
        Section section = result.getSections().get(0);
        assertThat(section.getHeading()).isEqualTo("Overview");
        assertThat(section.getContent()).contains("This is the overview section");
    }

    @Test
    @DisplayName("Should parse markdown with multiple headings")
    void testMarkdownParsing_MultipleHeadings() throws IssueParser.IssueParserException {
        // Given
        String body = """
            # Introduction
            
            Introduction content here.
            
            ## Requirements
            
            Requirements content here.
            
            ### Details
            
            Detailed information.
            """;
        GitHubIssue issue = createIssue("Test", body, Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getSections()).hasSize(3);
        assertThat(result.getSections().get(0).getHeading()).isEqualTo("Introduction");
        assertThat(result.getSections().get(1).getHeading()).isEqualTo("Requirements");
        assertThat(result.getSections().get(2).getHeading()).isEqualTo("Details");
    }

    @Test
    @DisplayName("Should parse markdown with bullet lists")
    void testMarkdownParsing_BulletLists() throws IssueParser.IssueParserException {
        // Given
        String body = """
            # Features
            
            - Feature 1
            - Feature 2
            - Feature 3
            """;
        GitHubIssue issue = createIssue("Test", body, Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getSections()).hasSize(1);
        Section section = result.getSections().get(0);
        assertThat(section.getHeading()).isEqualTo("Features");
        assertThat(section.getContent()).contains("Feature 1", "Feature 2", "Feature 3");
    }

    @Test
    @DisplayName("Should parse markdown with ordered lists")
    void testMarkdownParsing_OrderedLists() throws IssueParser.IssueParserException {
        // Given
        String body = """
            # Steps
            
            1. First step
            2. Second step
            3. Third step
            """;
        GitHubIssue issue = createIssue("Test", body, Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getSections()).hasSize(1);
        Section section = result.getSections().get(0);
        assertThat(section.getContent()).contains("First step", "Second step", "Third step");
    }

    @Test
    @DisplayName("Should parse markdown with code blocks")
    void testMarkdownParsing_CodeBlocks() throws IssueParser.IssueParserException {
        // Given
        String body = """
            # Example
            
            Here is some code:
            
            ```java
            public class Example {
                public void test() {}
            }
            ```
            """;
        GitHubIssue issue = createIssue("Test", body, Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getSections()).hasSize(1);
        Section section = result.getSections().get(0);
        assertThat(section.getContent()).contains("```", "public class Example");
    }

    @Test
    @DisplayName("Should parse markdown with inline code")
    void testMarkdownParsing_InlineCode() throws IssueParser.IssueParserException {
        // Given
        String body = """
            # Usage
            
            Use the `parseIssue()` method to parse issues.
            """;
        GitHubIssue issue = createIssue("Test", body, Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getSections()).hasSize(1);
        Section section = result.getSections().get(0);
        assertThat(section.getContent()).contains("`parseIssue()`");
    }

    @Test
    @DisplayName("Should parse markdown with mixed content")
    void testMarkdownParsing_MixedContent() throws IssueParser.IssueParserException {
        // Given
        String body = """
            # Overview
            
            This section has multiple paragraphs.
            
            And bullet points:
            - Point 1
            - Point 2
            
            And some `inline code`.
            
            # Implementation
            
            Implementation details here.
            """;
        GitHubIssue issue = createIssue("Test", body, Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getSections()).hasSize(2);
        assertThat(result.getSections().get(0).getHeading()).isEqualTo("Overview");
        assertThat(result.getSections().get(1).getHeading()).isEqualTo("Implementation");
    }

    // ===== Edge Cases =====

    @Test
    @DisplayName("Should handle empty body")
    void testEdgeCase_EmptyBody() throws IssueParser.IssueParserException {
        // Given
        GitHubIssue issue = createIssue("Test", "", Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getBody()).isEmpty();
        assertThat(result.getSections()).isEmpty();
    }

    @Test
    @DisplayName("Should handle body with only whitespace")
    void testEdgeCase_WhitespaceBody() throws IssueParser.IssueParserException {
        // Given
        GitHubIssue issue = createIssue("Test", "   \n\n   ", Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getSections()).isEmpty();
    }

    @Test
    @DisplayName("Should handle body without headings")
    void testEdgeCase_NoHeadings() throws IssueParser.IssueParserException {
        // Given
        String body = """
            This is just plain text without any headings.
            
            Multiple paragraphs but no structure.
            """;
        GitHubIssue issue = createIssue("Test", body, Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getSections()).isEmpty();
        assertThat(result.getBody()).isEqualTo(body);
    }

    @Test
    @DisplayName("Should handle very long body content")
    void testEdgeCase_LongBody() throws IssueParser.IssueParserException {
        // Given
        StringBuilder longBody = new StringBuilder("# Long Section\n\n");
        for (int i = 0; i < 1000; i++) {
            longBody.append("Line ").append(i).append("\n");
        }
        GitHubIssue issue = createIssue("Test", longBody.toString(), Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getSections()).hasSize(1);
        assertThat(result.getSections().get(0).getContent()).contains("Line 0", "Line 999");
    }

    @Test
    @DisplayName("Should handle markdown with special characters")
    void testEdgeCase_SpecialCharacters() throws IssueParser.IssueParserException {
        // Given
        String body = """
            # Special Characters
            
            Content with <html>, &amp;, "quotes", and 'apostrophes'.
            """;
        GitHubIssue issue = createIssue("Test", body, Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getSections()).hasSize(1);
        // CommonMark parser processes HTML entities, so we check for the parsed content
        String content = result.getSections().get(0).getContent();
        assertThat(content).contains("quotes", "apostrophes");
        // Verify the section was parsed (heading and content exist)
        assertThat(result.getSections().get(0).getHeading()).isEqualTo("Special Characters");
    }

    // ===== Error Handling Tests =====

    @Test
    @DisplayName("Should throw exception for null issue")
    void testErrorHandling_NullIssue() {
        // When/Then
        assertThatThrownBy(() -> parser.parseIssue(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessageContaining("GitHub issue cannot be null");
    }

    @Test
    @DisplayName("Should preserve original body content")
    void testBodyPreservation() throws IssueParser.IssueParserException {
        // Given
        String originalBody = """
            # Test
            
            Original content that should be preserved exactly.
            """;
        GitHubIssue issue = createIssue("Test", originalBody, Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getBody()).isEqualTo(originalBody);
    }

    @Test
    @DisplayName("Should create defensive copy of labels")
    void testDefensiveCopy_Labels() throws IssueParser.IssueParserException {
        // Given
        List<String> originalLabels = Arrays.asList("label1", "label2");
        GitHubIssue issue = createIssue("Test", "Body", originalLabels, "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        List<String> parsedLabels = result.getMetadata().getLabels();
        assertThat(parsedLabels).containsExactlyElementsOf(originalLabels);
        // Verify it's a different list instance (defensive copy)
        assertThat(parsedLabels).isNotSameAs(originalLabels);
    }

    // ===== Complex Structure Tests =====

    @Test
    @DisplayName("Should parse nested heading structure")
    void testComplexStructure_NestedHeadings() throws IssueParser.IssueParserException {
        // Given
        String body = """
            # Main Section
            
            Main content.
            
            ## Subsection 1
            
            Subsection 1 content.
            
            ### Sub-subsection
            
            Nested content.
            
            ## Subsection 2
            
            Subsection 2 content.
            """;
        GitHubIssue issue = createIssue("Test", body, Collections.emptyList(), "repo", 1);

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getSections()).hasSize(4);
        assertThat(result.getSections().get(0).getHeading()).isEqualTo("Main Section");
        assertThat(result.getSections().get(1).getHeading()).isEqualTo("Subsection 1");
        assertThat(result.getSections().get(2).getHeading()).isEqualTo("Sub-subsection");
        assertThat(result.getSections().get(3).getHeading()).isEqualTo("Subsection 2");
    }

    @Test
    @DisplayName("Should parse real-world issue format")
    void testRealWorld_TypicalIssueFormat() throws IssueParser.IssueParserException {
        // Given
        String body = """
            ## Description
            
            As a user, I want to authenticate using OAuth, so that I can securely access the system.
            
            ## Acceptance Criteria
            
            - User can log in with OAuth provider
            - Session is created upon successful authentication
            - Invalid credentials are rejected
            
            ## Technical Notes
            
            Use Spring Security OAuth2 integration.
            
            ```java
            @Configuration
            public class SecurityConfig {
                // Configuration here
            }
            ```
            """;
        GitHubIssue issue = createIssue(
            "[BACKEND] [STORY] OAuth Authentication",
            body,
            Arrays.asList("backend", "story", "authentication"),
            "durion-positivity-backend",
            456
        );

        // When
        ParsedIssue result = parser.parseIssue(issue);

        // Then
        assertThat(result.getMetadata().getTitle()).contains("OAuth Authentication");
        assertThat(result.getSections()).hasSize(3);
        assertThat(result.getSections().get(0).getHeading()).isEqualTo("Description");
        assertThat(result.getSections().get(1).getHeading()).isEqualTo("Acceptance Criteria");
        assertThat(result.getSections().get(2).getHeading()).isEqualTo("Technical Notes");
        assertThat(result.getSections().get(1).getContent()).contains("OAuth provider", "Session is created");
    }

    // ===== Helper Methods =====

    private GitHubIssue createIssue(String title, String body, List<String> labels, String repository, int number) {
        return new GitHubIssue(title, body, labels, repository, number);
    }
}
