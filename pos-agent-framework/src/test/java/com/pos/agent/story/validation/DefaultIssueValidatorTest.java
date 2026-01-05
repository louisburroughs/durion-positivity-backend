package com.pos.agent.story.validation;

import com.pos.agent.story.models.GitHubIssue;
import com.pos.agent.story.models.ValidationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultIssueValidatorTest {

    private DefaultIssueValidator validator;

    @BeforeEach
    void setUp() {
        validator = new DefaultIssueValidator();
    }

    @Test
    void validateIssue_ValidIssue_ReturnsValid() {
        GitHubIssue issue = new GitHubIssue(
                "[BACKEND] [STORY] User Authentication",
                "As a user, I want to authenticate...",
                List.of("story"),
                "durion-positivity-backend",
                123);

        ValidationResult result = validator.validateIssue(issue);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void validateIssue_ValidIssueWithFullRepoPath_ReturnsValid() {
        GitHubIssue issue = new GitHubIssue(
                "[BACKEND] [STORY] User Authentication",
                "As a user, I want to authenticate...",
                List.of("story"),
                "louisburroughs/durion-positivity-backend",
                123);

        ValidationResult result = validator.validateIssue(issue);

        assertThat(result.isValid()).isTrue();
    }

    @Test
    void validateIssue_InvalidRepository_ReturnsInvalid() {
        GitHubIssue issue = new GitHubIssue(
                "[BACKEND] [STORY] User Authentication",
                "As a user, I want to authenticate...",
                List.of("story"),
                "other-repo",
                123);

        ValidationResult result = validator.validateIssue(issue);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getStopPhrase())
                .isPresent()
                .get()
                .asString()
                .contains("Repository not in scope");
    }

    @Test
    void validateIssue_InvalidRepositoryWithOwner_ReturnsInvalid() {
        GitHubIssue issue = new GitHubIssue(
                "[BACKEND] [STORY] User Authentication",
                "As a user, I want to authenticate...",
                List.of("story"),
                "louisburroughs/other-repo",
                123);

        ValidationResult result = validator.validateIssue(issue);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getStopPhrase())
                .isPresent()
                .get()
                .asString()
                .contains("Repository not in scope");
    }

    @Test
    void validateIssue_MissingBackendPrefix_ReturnsInvalid() {
        GitHubIssue issue = new GitHubIssue(
                "[STORY] User Authentication",
                "As a user, I want to authenticate...",
                List.of("story"),
                "durion-positivity-backend",
                123);

        ValidationResult result = validator.validateIssue(issue);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getStopPhrase())
                .isPresent()
                .get()
                .asString()
                .contains("prefix not supported");
    }

    @Test
    void validateIssue_MissingStoryPrefix_ReturnsInvalid() {
        GitHubIssue issue = new GitHubIssue(
                "[BACKEND] User Authentication",
                "As a user, I want to authenticate...",
                List.of("story"),
                "durion-positivity-backend",
                123);

        ValidationResult result = validator.validateIssue(issue);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getStopPhrase())
                .isPresent()
                .get()
                .asString()
                .contains("prefix not supported");
    }

    @Test
    void validateIssue_NullIssue_ThrowsException() {
        assertThatThrownBy(() -> validator.validateIssue(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Issue cannot be null");
    }

    @Test
    void validateIssue_EpicLabel_ReturnsInvalid() {
        GitHubIssue issue = new GitHubIssue(
                "[BACKEND] [STORY] Epic Task",
                "This is an epic",
                List.of("epic"),
                "durion-positivity-backend",
                123);

        ValidationResult result = validator.validateIssue(issue);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getStopPhrase())
                .isPresent()
                .get()
                .asString()
                .contains("not a functional story");
    }

    @Test
    void validateIssue_BugLabel_ReturnsInvalid() {
        GitHubIssue issue = new GitHubIssue(
                "[BACKEND] [STORY] Bug Fix",
                "Fix the bug",
                List.of("bug"),
                "durion-positivity-backend",
                123);

        ValidationResult result = validator.validateIssue(issue);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getStopPhrase())
                .isPresent()
                .get()
                .asString()
                .contains("not a functional story");
    }
}
