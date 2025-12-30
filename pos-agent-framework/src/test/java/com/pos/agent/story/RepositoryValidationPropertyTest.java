package com.pos.agent.story;

import com.pos.agent.story.models.GitHubIssue;
import com.pos.agent.story.models.ValidationResult;
import com.pos.agent.story.validation.DefaultIssueValidator;
import com.pos.agent.story.validation.IssueValidator;
import net.jqwik.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for repository validation.
 * **Feature: upgrade-story-quality, Property 1: Repository validation**
 * **Validates: Requirements 1.1**
 */
class RepositoryValidationPropertyTest {

    private static final String VALID_REPOSITORY = "durion-positivity-backend";

    /**
     * Property 1: Repository validation
     * For any GitHub issue, the validation function should return true only when 
     * the repository name is "durion-positivity-backend".
     */
    @Property(tries = 100)
    void repositoryValidation(@ForAll("randomRepositoryNames") String repository) {
        // Given: An issue validator
        IssueValidator validator = new DefaultIssueValidator();

        // And: An issue with the given repository
        GitHubIssue issue = createIssueWithRepository(repository);

        // When: Validating the issue
        ValidationResult result = validator.validateIssue(issue);

        // Then: Validation should pass only for the valid repository
        if (VALID_REPOSITORY.equals(repository)) {
            // Valid repository should pass (if other conditions are met)
            // Note: May still fail due to other validation rules
            if (!result.isValid()) {
                // If it fails, it should not be due to repository
                assertThat(result.getStopPhrase())
                        .describedAs("Valid repository should not fail repository check")
                        .isPresent()
                        .get()
                        .asString()
                        .doesNotContain("Repository not in scope");
            }
        } else {
            // Invalid repository should fail with specific stop phrase
            assertThat(result.isValid())
                    .describedAs("Invalid repository '%s' should fail validation", repository)
                    .isFalse();

            assertThat(result.getStopPhrase())
                    .describedAs("Invalid repository should have stop phrase")
                    .isPresent()
                    .get()
                    .asString()
                    .contains("STOP: Repository not in scope");
        }
    }

    /**
     * Property: Only the exact valid repository passes
     */
    @Property(tries = 100)
    void onlyExactRepositoryPasses(@ForAll("similarRepositoryNames") String repository) {
        // Given: An issue validator
        IssueValidator validator = new DefaultIssueValidator();

        // And: An issue with a repository name similar to but not exactly the valid one
        GitHubIssue issue = createIssueWithRepository(repository);

        // When: Validating the issue
        ValidationResult result = validator.validateIssue(issue);

        // Then: Only the exact match should potentially pass
        if (!VALID_REPOSITORY.equals(repository)) {
            assertThat(result.isValid())
                    .describedAs("Repository '%s' should fail (not exact match)", repository)
                    .isFalse();
        }
    }

    /**
     * Property: Case sensitivity in repository validation
     */
    @Property(tries = 50)
    void repositoryValidationIsCaseSensitive() {
        // Given: An issue validator
        IssueValidator validator = new DefaultIssueValidator();

        // When: Testing with different case variations
        String[] variations = {
                "DURION-POSITIVITY-BACKEND",
                "Durion-Positivity-Backend",
                "durion-POSITIVITY-backend",
                "durion-positivity-BACKEND"
        };

        for (String variation : variations) {
            GitHubIssue issue = createIssueWithRepository(variation);
            ValidationResult result = validator.validateIssue(issue);

            // Then: Only exact case should pass
            if (!VALID_REPOSITORY.equals(variation)) {
                assertThat(result.isValid())
                        .describedAs("Repository '%s' should fail (case mismatch)", variation)
                        .isFalse();
            }
        }
    }

    // ========== Arbitraries ==========

    @Provide
    Arbitrary<String> randomRepositoryNames() {
        return Arbitraries.oneOf(
                Arbitraries.just(VALID_REPOSITORY),
                Arbitraries.of(
                        "durion-moqui-frontend",
                        "workspace-agents",
                        "durion-backend",
                        "positivity-backend",
                        "durion-positivity",
                        "other-repository",
                        "random-repo",
                        ""
                ),
                Arbitraries.strings()
                        .withCharRange('a', 'z')
                        .withChars('-', '_')
                        .ofMinLength(5)
                        .ofMaxLength(30)
        );
    }

    @Provide
    Arbitrary<String> similarRepositoryNames() {
        return Arbitraries.of(
                "durion-positivity-backend",  // exact match
                "DURION-POSITIVITY-BACKEND",  // uppercase
                "Durion-Positivity-Backend",  // title case
                "durion-positivity-backend ", // trailing space
                " durion-positivity-backend", // leading space
                "durion-positivity-backends", // plural
                "durion-positivity-backen",   // missing char
                "durion_positivity_backend",  // underscore instead of dash
                "durion.positivity.backend"   // dots instead of dashes
        );
    }

    // ========== Helper Methods ==========

    private GitHubIssue createIssueWithRepository(String repository) {
        return new GitHubIssue(
                "[BACKEND] [STORY] Test Issue",
                "As a user, I want to test validation, so that I can verify the system works correctly.",
                List.of("story", "backend"),
                repository,
                123
        );
    }
}
