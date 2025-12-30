package com.pos.agent.story.validation;

import com.pos.agent.story.models.GitHubIssue;
import com.pos.agent.story.models.ValidationResult;

/**
 * Interface for validating GitHub issues for Story Strengthening Agent processing.
 * Validates repository, issue prefix, and story type.
 * 
 * Requirements: 1.1, 1.2, 1.3, 1.4, 1.5
 */
public interface IssueValidator {
    
    /**
     * Validates a GitHub issue for processing eligibility.
     * Checks repository name, issue prefix format, and story type.
     * 
     * @param issue The GitHub issue to validate
     * @return ValidationResult indicating if the issue is valid for processing
     */
    ValidationResult validateIssue(GitHubIssue issue);
}
