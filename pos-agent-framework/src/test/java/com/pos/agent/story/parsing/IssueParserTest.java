package com.pos.agent.story.parsing;

import com.pos.agent.story.models.GitHubIssue;
import com.pos.agent.story.models.ParsedIssue;
import com.pos.agent.story.models.ParsedIssue.IssueMetadata;
import com.pos.agent.story.models.ParsedIssue.Section;
import com.pos.agent.story.parsing.IssueParser.IssueParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for IssueParser component.
 * Tests metadata extraction, markdown parsing, and section identification.
 * 
 * Requirements: 2.1, 2.2, 2.3, 2.4
 */
@DisplayName("IssueParser Unit Tests")
class IssueParserTest {

    private IssueParser parser;

    @BeforeEach
    void setUp() {
        parser = new IssueParser();
    }

    // ========== Metadata Extraction Tests (Requirements: 2.1, 2.3, 2.4) ==========

    @Test
    @DisplayName("Should extract title from GitHub issue")
    void testExtractTitle() throws IssueParserException {
        // Given
        GitHubIssue issue = new GitHubIssue(
            "[BACKEND] [STORY] User Authentication",
            "Issue body content",
            Arrays.asList("backend", "story"),
            "durion-positivity-backend",
            123
        );

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals("[BACKEND] [STORY] User Authentication", parsed.getMetadata().getTitle());
    }

    @Test
    @DisplayName("Should extract labels from GitHub issue")
    void testExtractLabels() throws IssueParserException {
        // Given
        List<String> labels = Arrays.asList("backend", "story", "enhancement");
        GitHubIssue issue = new GitHubIssue(
            "[BACKEND] [STORY] Feature",
            "Body",
            labels,
            "durion-positivity-backend",
            456
        );

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals(3, parsed.getMetadata().getLabels().size());
        assertTrue(parsed.getMetadata().getLabels().contains("backend"));
        assertTrue(parsed.getMetadata().getLabels().contains("story"));
        assertTrue(parsed.getMetadata().getLabels().contains("enhancement"));
    }

    @Test
    @DisplayName("Should extract repository from GitHub issue")
    void testExtractRepository() throws IssueParserException {
        // Given
        GitHubIssue issue = new GitHubIssue(
            "Title",
            "Body",
            Collections.emptyList(),
            "durion-positivity-backend",
            789
        );

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals("durion-positivity-backend", parsed.getMetadata().getRepository());
    }

    @Test
    @DisplayName("Should extract all metadata fields correctly")
    void testExtractAllMetadata() throws IssueParserException {
        // Given
        GitHubIssue issue = new GitHubIssue(
            "[BACKEND] [STORY] Complete Feature",
            "Detailed body content",
            Arrays.asList("backend", "story", "priority-high"),
            "durion-positivity-backend",
            999
        );

        // When
        ParsedIssue parsed = parser.parseIssue(issue);
        IssueMetadata metadata = parsed.getMetadata();

        // Then - verify all fields are captured
        assertNotNull(metadata);
        assertEquals("[BACKEND] [STORY] Complete Feature", metadata.getTitle());
        assertEquals("durion-positivity-backend", metadata.getRepository());
        assertEquals(3, metadata.getLabels().size());
        assertNotNull(parsed.getBody());
        assertEquals("Detailed body content", parsed.getBody());
    }

    // ========== Markdown Parsing Tests (Requirements: 2.2) ==========

    @Test
    @DisplayName("Should parse simple markdown with one heading")
    void testParseSimpleMarkdown() throws IssueParserException {
        // Given
        String body = "# Introduction\n\nThis is the introduction section.";
        GitHubIssue issue = createTestIssue(body);

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals(1, parsed.getSections().size());
        Section section = parsed.getSections().get(0);
        assertEquals("Introduction", section.getHeading());
        assertEquals("This is the introduction section.", section.getContent());
    }

    @Test
    @DisplayName("Should parse markdown with multiple headings")
    void testParseMultipleHeadings() throws IssueParserException {
        // Given
        String body = """
            # Overview
            This is the overview.
            
            ## Requirements
            These are the requirements.
            
            ### Acceptance Criteria
            These are the acceptance criteria.
            """;
        GitHubIssue issue = createTestIssue(body);

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals(3, parsed.getSections().size());
        
        assertEquals("Overview", parsed.getSections().get(0).getHeading());
        assertEquals("This is the overview.", parsed.getSections().get(0).getContent());
        
        assertEquals("Requirements", parsed.getSections().get(1).getHeading());
        assertEquals("These are the requirements.", parsed.getSections().get(1).getContent());
        
        assertEquals("Acceptance Criteria", parsed.getSections().get(2).getHeading());
        assertEquals("These are the acceptance criteria.", parsed.getSections().get(2).getContent());
    }

    @Test
    @DisplayName("Should parse markdown with bullet lists")
    void testParseMarkdownWithBulletLists() throws IssueParserException {
        // Given
        String body = """
            # Features
            
            - Feature 1
            - Feature 2
            - Feature 3
            """;
        GitHubIssue issue = createTestIssue(body);

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals(1, parsed.getSections().size());
        Section section = parsed.getSections().get(0);
        assertEquals("Features", section.getHeading());
        assertTrue(section.getContent().contains("Feature 1"));
        assertTrue(section.getContent().contains("Feature 2"));
        assertTrue(section.getContent().contains("Feature 3"));
    }

    @Test
    @DisplayName("Should parse markdown with code blocks")
    void testParseMarkdownWithCodeBlocks() throws IssueParserException {
        // Given
        String body = """
            # Example
            
            Here is some code:
            
            ```java
            public class Example {
                // code here
            }
            ```
            """;
        GitHubIssue issue = createTestIssue(body);

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals(1, parsed.getSections().size());
        Section section = parsed.getSections().get(0);
        assertEquals("Example", section.getHeading());
        assertTrue(section.getContent().contains("Here is some code:"));
        assertTrue(section.getContent().contains("```"));
    }

    @Test
    @DisplayName("Should parse markdown with inline code")
    void testParseMarkdownWithInlineCode() throws IssueParserException {
        // Given
        String body = """
            # Configuration
            
            Set the `API_KEY` environment variable.
            """;
        GitHubIssue issue = createTestIssue(body);

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals(1, parsed.getSections().size());
        Section section = parsed.getSections().get(0);
        assertEquals("Configuration", section.getHeading());
        assertTrue(section.getContent().contains("`API_KEY`"));
    }

    @Test
    @DisplayName("Should handle empty body")
    void testParseEmptyBody() throws IssueParserException {
        // Given
        GitHubIssue issue = createTestIssue("");

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals(0, parsed.getSections().size());
        assertEquals("", parsed.getBody());
    }

    @Test
    @DisplayName("Should handle body with no headings")
    void testParseBodyWithNoHeadings() throws IssueParserException {
        // Given
        String body = "This is just plain text without any headings.";
        GitHubIssue issue = createTestIssue(body);

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals(0, parsed.getSections().size());
        assertEquals(body, parsed.getBody());
    }

    @Test
    @DisplayName("Should parse complex markdown structure")
    void testParseComplexMarkdown() throws IssueParserException {
        // Given
        String body = """
            # User Story
            
            As a user, I want to authenticate so that I can access the system.
            
            ## Acceptance Criteria
            
            1. User can log in with email and password
            2. User receives JWT token on successful login
            3. Invalid credentials return error message
            
            ## Technical Notes
            
            - Use bcrypt for password hashing
            - JWT expiration: 24 hours
            - Rate limiting: 5 attempts per minute
            
            ## Implementation Details
            
            The authentication service should:
            
            ```java
            public interface AuthService {
                Token authenticate(String email, String password);
            }
            ```
            """;
        GitHubIssue issue = createTestIssue(body);

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals(4, parsed.getSections().size());
        
        // Verify section headings
        assertEquals("User Story", parsed.getSections().get(0).getHeading());
        assertEquals("Acceptance Criteria", parsed.getSections().get(1).getHeading());
        assertEquals("Technical Notes", parsed.getSections().get(2).getHeading());
        assertEquals("Implementation Details", parsed.getSections().get(3).getHeading());
        
        // Verify content is captured
        assertTrue(parsed.getSections().get(0).getContent().contains("As a user"));
        assertTrue(parsed.getSections().get(1).getContent().contains("JWT token"));
        assertTrue(parsed.getSections().get(2).getContent().contains("bcrypt"));
        assertTrue(parsed.getSections().get(3).getContent().contains("AuthService"));
    }

    // ========== Edge Cases and Error Handling ==========

    @Test
    @DisplayName("Should throw exception when issue is null")
    void testParseNullIssue() {
        // When/Then
        assertThrows(NullPointerException.class, () -> parser.parseIssue(null));
    }

    @Test
    @DisplayName("Should handle special characters in title")
    void testParseSpecialCharactersInTitle() throws IssueParserException {
        // Given
        GitHubIssue issue = new GitHubIssue(
            "[BACKEND] [STORY] Feature with <special> & \"characters\"",
            "Body",
            Collections.emptyList(),
            "durion-positivity-backend",
            100
        );

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals("[BACKEND] [STORY] Feature with <special> & \"characters\"", 
                    parsed.getMetadata().getTitle());
    }

    @Test
    @DisplayName("Should handle markdown with nested structures")
    void testParseNestedMarkdown() throws IssueParserException {
        // Given
        String body = """
            # Main Section
            
            ## Subsection 1
            
            Content for subsection 1.
            
            ### Sub-subsection
            
            Nested content.
            
            ## Subsection 2
            
            Content for subsection 2.
            """;
        GitHubIssue issue = createTestIssue(body);

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals(4, parsed.getSections().size());
        assertEquals("Main Section", parsed.getSections().get(0).getHeading());
        assertEquals("Subsection 1", parsed.getSections().get(1).getHeading());
        assertEquals("Sub-subsection", parsed.getSections().get(2).getHeading());
        assertEquals("Subsection 2", parsed.getSections().get(3).getHeading());
    }

    @Test
    @DisplayName("Should preserve original body content")
    void testPreserveOriginalBody() throws IssueParserException {
        // Given
        String originalBody = "# Test\n\nOriginal content with **formatting**.";
        GitHubIssue issue = createTestIssue(originalBody);

        // When
        ParsedIssue parsed = parser.parseIssue(issue);

        // Then
        assertEquals(originalBody, parsed.getBody());
    }

    // ========== Helper Methods ==========

    private GitHubIssue createTestIssue(String body) {
        return new GitHubIssue(
            "[BACKEND] [STORY] Test Issue",
            body,
            Arrays.asList("backend", "story"),
            "durion-positivity-backend",
            1
        );
    }
}
