package com.pos.agent.story;

import com.pos.agent.story.models.GitHubIssue;
import com.pos.agent.story.models.ValidationResult;
import com.pos.agent.story.validation.DefaultIssueValidator;
import com.pos.agent.story.validation.IssueValidator;
import net.jqwik.api.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for stop phrase emission on validation failure.
 * **Feature: upgrade-story-quality, Property 3: Stop phrase emission on validation failure**
 * **Validates: Requirements 1.4**
 */
class StopPhraseEmissionPropertyTest {

    /**
     * Property 3: Stop phrase emission on validation failure
     * For any GitHub issue that fails any activation condition, the system should 
     * emit a stop phrase and halt processing.
     */
    @Property(tries = 100)
    void stopPhraseEmissionOnValidationFailure(@ForAll("invalidIssues") GitHubIssue issue) {
        // Given: An issue validator
        IssueValidator validator = new DefaultIssueValidator();

        // When: Validating an invalid issue
        ValidationResult result = validator.validateIssue(issue);

        // Then: Validation should fail
        assertThat(result.isValid())
                .describedAs("Invalid issue should fail validation")
                .isFalse();

        // And: A stop phrase should be emitted
        assertThat(result.getStopPhrase())
                .describedAs("Failed validation should emit a stop phrase")
                .isPresent();

        // And: Stop phrase should start with "STOP:"
        assertThat(result.getStopPhrase().get())
                .describedAs("Stop phrase should start with 'STOP:'")
                .startsWith("STOP:");

        // And: A reason should be provided
        assertThat(result.getReason())
                .describedAs("Failed validation should provide a reason")
                .isPresent();
    }

    /**
     * Property: Each validation failure type has a specific stop phrase
     */
    @Property(tries = 50)
    void eachFailureTypeHasSpecificStopPhrase() {
        // Given: An issue validator
        IssueValidator validator = new DefaultIssueValidator();

        // When: Testing repository validation failure
        GitHubIssue invalidRepo = new GitHubIssue(
                "[BACKEND] [STORY] Test",
                "As a user, I want to test, so that validation works.",
                List.of("story"),
                "wrong-repository",
                1
        );
        ValidationResult repoResult = validator.validateIssue(invalidRepo);

        // Then: Should have repository-specific stop phrase
        assertThat(repoResult.getStopPhrase())
                .isPresent()
                .get()
                .asString()
                .contains("Repository not in scope");

        // When: Testing prefix validation failure
        GitHubIssue invalidPrefix = new GitHubIssue(
                "Missing Prefixes",
                "As a user, I want to test, so that validation works.",
                List.of("story"),
                "durion-positivity-backend",
                2
        );
        ValidationResult prefixResult = validator.validateIssue(invalidPrefix);

        // Then: Should have prefix-specific stop phrase
        assertThat(prefixResult.getStopPhrase())
                .isPresent()
                .get()
                .asString()
                .contains("Issue prefix not supported");

        // When: Testing story type validation failure
        GitHubIssue invalidType = new GitHubIssue(
                "[BACKEND] [STORY] Test",
                "Short",
                List.of("bug"),
                "durion-positivity-backend",
                3
        );
        ValidationResult typeResult = validator.validateIssue(invalidType);

        // Then: Should have story type-specific stop phrase
        assertThat(typeResult.getStopPhrase())
                .isPresent()
                .get()
                .asString()
                .contains("not a functional story");
    }

    /**
     * Property: Stop phrases are consistent across multiple validations
     */
    @Property(tries = 100)
    void stopPhrasesAreConsistent(@ForAll("invalidIssues") GitHubIssue issue) {
        // Given: An issue validator
        IssueValidator validator = new DefaultIssueValidator();

        // When: Validating the same issue multiple times
        ValidationResult result1 = validator.validateIssue(issue);
        ValidationResult result2 = validator.validateIssue(issue);

        // Then: Stop phrases should be identical
        assertThat(result1.getStopPhrase())
                .describedAs("Stop phrase should be consistent across validations")
                .isEqualTo(result2.getStopPhrase());

        // And: Reasons should be identical
        assertThat(result1.getReason())
                .describedAs("Reason should be consistent across validations")
                .isEqualTo(result2.getReason());
    }

    /**
     * Property: Valid issues do not emit stop phrases
     */
    @Property(tries = 100)
    void validIssuesDoNotEmitStopPhrases(@ForAll("validIssues") GitHubIssue issue) {
        // Given: An issue validator
        IssueValidator validator = new DefaultIssueValidator();

        // When: Validating a valid issue
        ValidationResult result = validator.validateIssue(issue);

        // Then: If validation passes, no stop phrase should be emitted
        if (result.isValid()) {
            assertThat(result.getStopPhrase())
                    .describedAs("Valid issue should not emit stop phrase")
                    .isEmpty();

            assertThat(result.getReason())
                    .describedAs("Valid issue should not have failure reason")
                    .isEmpty();
        }
    }

    // ========== Arbitraries ==========

    @Provide
    Arbitrary<GitHubIssue> invalidIssues() {
        return Arbitraries.oneOf(
                // Invalid repository
                invalidRepositoryIssues(),
                // Invalid prefix
                invalidPrefixIssues(),
                // Invalid story type
                invalidStoryTypeIssues()
        );
    }

    @Provide
    Arbitrary<GitHubIssue> invalidRepositoryIssues() {
        Arbitrary<String> invalidRepos = Arbitraries.of(
                "durion-moqui-frontend",
                "workspace-agents",
                "wrong-repo",
                "random-repository"
        );

        return invalidRepos.map(repo -> new GitHubIssue(
                "[BACKEND] [STORY] Test Issue",
                "As a user, I want to test validation, so that I can verify the system works.",
                List.of("story", "backend"),
                repo,
                123
        ));
    }

    @Provide
    Arbitrary<GitHubIssue> invalidPrefixIssues() {
        Arbitrary<String> invalidTitles = Arbitraries.of(
                "Missing All Prefixes",
                "[BACKEND] Missing Story",
                "[STORY] Missing Backend",
                "[FRONTEND] [STORY] Wrong Prefix",
                "[BACKEND] [TASK] Wrong Type"
        );

        return invalidTitles.map(title -> new GitHubIssue(
                title,
                "As a user, I want to test validation, so that I can verify the system works.",
                List.of("story", "backend"),
                "durion-positivity-backend",
                123
        ));
    }

    @Provide
    Arbitrary<GitHubIssue> invalidStoryTypeIssues() {
        Arbitrary<List<String>> invalidLabels = Arbitraries.of(
                List.of("bug"),
                List.of("task"),
                List.of("epic"),
                List.of("bug", "backend"),
                List.of("task", "story")
        );

        Arbitrary<String> invalidBodies = Arbitraries.of(
                "Too short",
                "",
                "No story indicators here",
                "Just a simple description without story format"
        );

        return Combinators.combine(invalidLabels, invalidBodies)
                .as((labels, body) -> new GitHubIssue(
                        "[BACKEND] [STORY] Test Issue",
                        body,
                        labels,
                        "durion-positivity-backend",
                        123
                ));
    }

    @Provide
    Arbitrary<GitHubIssue> validIssues() {
        Arbitrary<String> validTitles = Arbitraries.of(
                "[BACKEND] [STORY] Valid Issue",
                "[STORY] [BACKEND] Another Valid Issue"
        );

        Arbitrary<String> validBodies = Arbitraries.of(
                "As a user, I want to test validation, so that I can verify the system works correctly.",
                "As a developer, I want to implement features, so that users can benefit from new functionality.",
                "User story: As an admin, I want to manage users, so that I can control access."
        );

        return Combinators.combine(validTitles, validBodies)
                .as((title, body) -> new GitHubIssue(
                        title,
                        body,
                        List.of("story", "backend"),
                        "durion-positivity-backend",
                        123
                ));
    }
}
