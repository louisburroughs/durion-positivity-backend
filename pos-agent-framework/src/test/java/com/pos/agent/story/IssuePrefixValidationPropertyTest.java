package com.pos.agent.story;

import com.pos.agent.story.models.GitHubIssue;
import com.pos.agent.story.models.ValidationResult;
import com.pos.agent.story.validation.DefaultIssueValidator;
import com.pos.agent.story.validation.IssueValidator;
import net.jqwik.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for issue prefix validation.
 * **Feature: upgrade-story-quality, Property 2: Issue prefix validation**
 * **Validates: Requirements 1.2**
 */
class IssuePrefixValidationPropertyTest {

    private static final String VALID_REPOSITORY = "durion-positivity-backend";

    /**
     * Property 2: Issue prefix validation
     * For any GitHub issue title, the validation function should return true only when 
     * the title contains "[BACKEND] [STORY]".
     */
    @Property(tries = 100)
    void issuePrefixValidation(@ForAll("randomIssueTitles") String title) {
        // Given: An issue validator
        IssueValidator validator = new DefaultIssueValidator();

        // And: An issue with the given title
        GitHubIssue issue = createIssueWithTitle(title);

        // When: Validating the issue
        ValidationResult result = validator.validateIssue(issue);

        // Then: Validation should pass only if title contains both required prefixes
        boolean hasBackendPrefix = title != null && title.contains("[BACKEND]");
        boolean hasStoryPrefix = title != null && title.contains("[STORY]");
        boolean hasValidPrefix = hasBackendPrefix && hasStoryPrefix;

        if (!hasValidPrefix) {
            // Invalid prefix should fail with specific stop phrase
            assertThat(result.isValid())
                    .describedAs("Title '%s' without valid prefix should fail validation", title)
                    .isFalse();

            assertThat(result.getStopPhrase())
                    .describedAs("Invalid prefix should have stop phrase")
                    .isPresent()
                    .get()
                    .asString()
                    .contains("STOP: Issue prefix not supported");
        } else {
            // Valid prefix should not fail due to prefix check
            // (may still fail due to other validation rules)
            if (!result.isValid()) {
                assertThat(result.getStopPhrase())
                        .describedAs("Valid prefix should not fail prefix check")
                        .isPresent()
                        .get()
                        .asString()
                        .doesNotContain("Issue prefix not supported");
            }
        }
    }

    /**
     * Property: Both prefixes are required
     */
    @Property(tries = 100)
    void bothPrefixesRequired(@ForAll("titlesWithSinglePrefix") String title) {
        // Given: An issue validator
        IssueValidator validator = new DefaultIssueValidator();

        // And: An issue with only one of the required prefixes
        GitHubIssue issue = createIssueWithTitle(title);

        // When: Validating the issue
        ValidationResult result = validator.validateIssue(issue);

        // Then: Should fail if missing either prefix
        boolean hasBackendPrefix = title.contains("[BACKEND]");
        boolean hasStoryPrefix = title.contains("[STORY]");

        if (!hasBackendPrefix || !hasStoryPrefix) {
            assertThat(result.isValid())
                    .describedAs("Title '%s' with only one prefix should fail", title)
                    .isFalse();
        }
    }

    /**
     * Property: Prefix order doesn't matter
     */
    @Property(tries = 50)
    void prefixOrderDoesNotMatter() {
        // Given: An issue validator
        IssueValidator validator = new DefaultIssueValidator();

        // When: Testing with different prefix orders
        String[] titleVariations = {
                "[BACKEND] [STORY] Test Issue",
                "[STORY] [BACKEND] Test Issue",
                "Test [BACKEND] Issue [STORY]",
                "[STORY] Test [BACKEND] Issue"
        };

        for (String title : titleVariations) {
            GitHubIssue issue = createIssueWithTitle(title);
            ValidationResult result = validator.validateIssue(issue);

            // Then: All should pass prefix validation (may fail other checks)
            if (!result.isValid()) {
                assertThat(result.getStopPhrase())
                        .describedAs("Title '%s' should not fail prefix check", title)
                        .isPresent()
                        .get()
                        .asString()
                        .doesNotContain("Issue prefix not supported");
            }
        }
    }

    /**
     * Property: Case sensitivity in prefix validation
     */
    @Property(tries = 50)
    void prefixValidationIsCaseSensitive() {
        // Given: An issue validator
        IssueValidator validator = new DefaultIssueValidator();

        // When: Testing with different case variations
        String[] titleVariations = {
                "[backend] [story] Test Issue",
                "[Backend] [Story] Test Issue",
                "[BACKEND] [story] Test Issue",
                "[backend] [STORY] Test Issue"
        };

        for (String title : titleVariations) {
            GitHubIssue issue = createIssueWithTitle(title);
            ValidationResult result = validator.validateIssue(issue);

            // Then: Only exact case should pass
            if (!title.contains("[BACKEND]") || !title.contains("[STORY]")) {
                assertThat(result.isValid())
                        .describedAs("Title '%s' should fail (case mismatch)", title)
                        .isFalse();
            }
        }
    }

    // ========== Arbitraries ==========

    @Provide
    Arbitrary<String> randomIssueTitles() {
        return Arbitraries.oneOf(
                // Valid titles
                Arbitraries.of(
                        "[BACKEND] [STORY] Valid Issue",
                        "[STORY] [BACKEND] Another Valid Issue",
                        "Prefix [BACKEND] in middle [STORY]"
                ),
                // Invalid titles - missing one or both prefixes
                Arbitraries.of(
                        "[BACKEND] Missing Story Prefix",
                        "[STORY] Missing Backend Prefix",
                        "No Prefixes At All",
                        "[FRONTEND] [STORY] Wrong Prefix",
                        "[BACKEND] [TASK] Wrong Type",
                        ""
                ),
                // Random titles
                Arbitraries.strings()
                        .withCharRange('a', 'z')
                        .withCharRange('A', 'Z')
                        .withChars(' ', '-', '_', '[', ']')
                        .ofMinLength(10)
                        .ofMaxLength(100)
        );
    }

    @Provide
    Arbitrary<String> titlesWithSinglePrefix() {
        return Arbitraries.of(
                "[BACKEND] Test Issue",
                "[STORY] Test Issue",
                "Test [BACKEND] Issue",
                "Test [STORY] Issue",
                "[FRONTEND] [STORY] Test",
                "[BACKEND] [TASK] Test"
        );
    }

    // ========== Helper Methods ==========

    private GitHubIssue createIssueWithTitle(String title) {
        return new GitHubIssue(
                title,
                "As a user, I want to test validation, so that I can verify the system works correctly.",
                List.of("story", "backend"),
                VALID_REPOSITORY,
                123
        );
    }
}
