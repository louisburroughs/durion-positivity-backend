package com.pos.agent.story.output;


import com.pos.agent.story.models.TransformedRequirements;
import net.jqwik.api.*;

import java.util.Collections;


import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based test for original content preservation (round-trip).
 * Validates that the original story text appears verbatim in the output.
 * 
 * Feature: upgrade-story-quality, Property 8: Original content preservation
 * Validates: Requirements 3.3, 3.4, 7.5, 9.1, 9.2, 9.3
 */
class OriginalContentPreservationPropertyTest {

    private final OutputGenerator generator = new OutputGenerator();

    /**
     * Property 8: Original content preservation (round-trip)
     * For any original story text, it should appear verbatim in the output.
     * 
     * Validates: Requirements 3.3, 3.4, 7.5, 9.1, 9.2, 9.3
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 8: Original content preservation")
    void originalStory_AppearsVerbatimInOutput(@ForAll("originalStories") String originalStory) {
        
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify original story appears verbatim in output
        assertTrue(output.contains(originalStory),
                  "Output should contain original story verbatim");
    }

    /**
     * Property 8 (variant): Original story section is at the end
     * For any output, the original story section should be the last section.
     * 
     * Validates: Requirements 3.3, 3.4, 9.1
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 8: Original story at end")
    void originalStorySection_IsLastSection(@ForAll("originalStories") String originalStory) {
        
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Find the original story section
        int originalStorySectionIndex = output.indexOf("## Original Story (Unmodified – For Traceability)");
        assertTrue(originalStorySectionIndex > 0, "Original story section should be present");
        
        // Verify no other ## headers appear after the original story section
        String afterOriginalStory = output.substring(originalStorySectionIndex + 50);
        assertFalse(afterOriginalStory.contains("\n## "),
                   "No other sections should appear after original story");
    }

    /**
     * Property 8 (variant): Original story is not modified
     * For any original story with special characters, it should not be escaped or modified.
     * 
     * Validates: Requirements 9.2, 9.3
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 8: No modification of original")
    void originalStory_IsNotModified(@ForAll("storiesWithSpecialChars") String originalStory) {
        
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify original story appears exactly as provided (not escaped or modified)
        assertTrue(output.contains(originalStory),
                  "Original story should not be modified or escaped");
    }

    /**
     * Property 8 (variant): Empty original story is preserved
     * For empty original story, the section should still be present.
     * 
     * Validates: Requirements 3.3, 9.1
     */
    @Property(tries = 50)
    @Label("Feature: upgrade-story-quality, Property 8: Empty original preserved")
    void emptyOriginalStory_IsPreserved() {
        
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        
        String output = generator.generateOutput(transformed, "");
        
        // Verify original story section is present even if empty
        assertTrue(output.contains("## Original Story (Unmodified – For Traceability)"),
                  "Original story section should be present even if empty");
    }

    /**
     * Property 8 (variant): Original story with markdown is preserved
     * For original story containing markdown, it should not be re-parsed or modified.
     * 
     * Validates: Requirements 9.2, 9.3
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 8: Markdown preserved")
    void originalStoryWithMarkdown_IsPreserved(@ForAll("storiesWithMarkdown") String originalStory) {
        
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify markdown in original story is preserved exactly
        assertTrue(output.contains(originalStory),
                  "Markdown in original story should be preserved exactly");
    }

    /**
     * Property 8 (variant): Multiple newlines are preserved
     * For original story with multiple newlines, they should be preserved.
     * 
     * Validates: Requirements 9.2, 9.3
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 8: Newlines preserved")
    void originalStoryNewlines_ArePreserved(@ForAll("storiesWithNewlines") String originalStory) {
        
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify newlines are preserved
        assertTrue(output.contains(originalStory),
                  "Newlines in original story should be preserved");
    }

    /**
     * Property 8 (variant): Validation passes for outputs with original story
     * For any output with original story, validation should pass.
     * 
     * Validates: Requirements 3.3, 9.1
     */
    @Property(tries = 100)
    @Label("Feature: upgrade-story-quality, Property 8: Validation passes")
    void outputWithOriginalStory_PassesValidation(@ForAll("originalStories") String originalStory) {
        
        TransformedRequirements transformed = createMinimalTransformedRequirements();
        
        String output = generator.generateOutput(transformed, originalStory);
        
        // Verify output passes validation
        assertTrue(generator.validateOutput(output),
                  "Output with original story should pass validation");
    }

    // ========== Generators ==========

    @Provide
    Arbitrary<String> originalStories() {
        return Arbitraries.of(
                "As a user, I want to create an order so that I can purchase products.",
                "The system should validate user input before processing.",
                "This is a simple story with basic requirements.",
                "Story with multiple lines\nand different sections\nthat need to be preserved.",
                "Story with special characters: @#$%^&*()",
                "Story with numbers: 123, 456, 789",
                "Story with URLs: https://example.com/api/v1/orders",
                "Story with code: `const x = 42;`",
                "Story with emphasis: **bold** and *italic*",
                "Story with lists:\n- Item 1\n- Item 2\n- Item 3"
        );
    }

    @Provide
    Arbitrary<String> storiesWithSpecialChars() {
        return Arbitraries.of(
                "Story with <html> tags",
                "Story with & ampersand",
                "Story with \"quotes\"",
                "Story with 'single quotes'",
                "Story with backslash \\",
                "Story with forward slash /",
                "Story with pipe |",
                "Story with brackets []",
                "Story with braces {}",
                "Story with parentheses ()"
        );
    }

    @Provide
    Arbitrary<String> storiesWithMarkdown() {
        return Arbitraries.of(
                "# Heading 1\n## Heading 2\n### Heading 3",
                "**Bold text** and *italic text*",
                "- Bullet list\n- Item 2\n- Item 3",
                "1. Numbered list\n2. Item 2\n3. Item 3",
                "[Link text](https://example.com)",
                "![Image alt](https://example.com/image.png)",
                "`inline code` and ```code block```",
                "> Blockquote text",
                "---\nHorizontal rule",
                "| Table | Header |\n|-------|--------|\n| Cell  | Cell   |"
        );
    }

    @Provide
    Arbitrary<String> storiesWithNewlines() {
        return Arbitraries.of(
                "Line 1\nLine 2\nLine 3",
                "Paragraph 1\n\nParagraph 2",
                "Multiple\n\n\nnewlines",
                "Trailing newline\n",
                "\nLeading newline",
                "Mixed\n\nspacing\n\n\npatterns"
        );
    }

    // ========== Helper Methods ==========

    private TransformedRequirements createMinimalTransformedRequirements() {
        return new TransformedRequirements(
                "# Test Story",
                "Test intent",
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }
}
