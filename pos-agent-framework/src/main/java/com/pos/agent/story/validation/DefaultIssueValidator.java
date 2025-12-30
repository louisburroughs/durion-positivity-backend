package com.pos.agent.story.validation;

import com.pos.agent.story.models.GitHubIssue;
import com.pos.agent.story.models.ValidationResult;

import java.util.Objects;

/**
 * Default implementation of IssueValidator.
 * Validates GitHub issues for Story Strengthening Agent processing.
 * 
 * Validation rules:
 * - Repository must be "durion-positivity-backend"
 * - Issue title must contain "[BACKEND] [STORY]"
 * - Issue body must represent a functional story (not epic/task/bug)
 * 
 * Requirements: 1.1, 1.2, 1.3, 1.4, 1.5
 */
public class DefaultIssueValidator implements IssueValidator {
    
    private static final String VALID_REPOSITORY = "durion-positivity-backend";
    private static final String REQUIRED_PREFIX_BACKEND = "[BACKEND]";
    private static final String REQUIRED_PREFIX_STORY = "[STORY]";
    
    // Stop phrases as defined in requirements
    private static final String STOP_REPOSITORY_NOT_IN_SCOPE = "STOP: Repository not in scope";
    private static final String STOP_PREFIX_NOT_SUPPORTED = "STOP: Issue prefix not supported";
    private static final String STOP_NOT_FUNCTIONAL_STORY = "STOP: Issue is not a functional story";
    
    /**
     * Validates a GitHub issue for processing eligibility.
     * 
     * @param issue The GitHub issue to validate
     * @return ValidationResult indicating if the issue is valid for processing
     * @throws NullPointerException if issue is null
     */
    @Override
    public ValidationResult validateIssue(GitHubIssue issue) {
        Objects.requireNonNull(issue, "Issue cannot be null");
        
        // Requirement 1.1: Verify repository is durion-positivity-backend
        if (!isValidRepository(issue.getRepository())) {
            return ValidationResult.invalid(
                STOP_REPOSITORY_NOT_IN_SCOPE,
                String.format("Repository '%s' is not in scope. Expected: '%s'", 
                            issue.getRepository(), VALID_REPOSITORY)
            );
        }
        
        // Requirement 1.2: Verify issue title contains [BACKEND] [STORY]
        if (!hasValidPrefix(issue.getTitle())) {
            return ValidationResult.invalid(
                STOP_PREFIX_NOT_SUPPORTED,
                String.format("Issue title '%s' does not contain required prefixes [BACKEND] [STORY]", 
                            issue.getTitle())
            );
        }
        
        // Requirement 1.3: Verify issue body represents a functional story
        if (!isFunctionalStory(issue)) {
            return ValidationResult.invalid(
                STOP_NOT_FUNCTIONAL_STORY,
                "Issue does not represent a functional story (may be epic, task, or bug)"
            );
        }
        
        // Requirement 1.5: All activation conditions met, proceed with processing
        return ValidationResult.valid();
    }
    
    /**
     * Checks if the repository name is valid.
     * 
     * @param repository The repository name to check
     * @return true if repository is "durion-positivity-backend"
     */
    private boolean isValidRepository(String repository) {
        return VALID_REPOSITORY.equals(repository);
    }
    
    /**
     * Checks if the issue title contains the required prefixes.
     * 
     * @param title The issue title to check
     * @return true if title contains both [BACKEND] and [STORY]
     */
    private boolean hasValidPrefix(String title) {
        return title != null && 
               title.contains(REQUIRED_PREFIX_BACKEND) && 
               title.contains(REQUIRED_PREFIX_STORY);
    }
    
    /**
     * Determines if the issue represents a functional story.
     * 
     * Heuristics:
     * - Labels should not contain "epic", "task", or "bug"
     * - Body should contain story-like language (user story format)
     * - Body should not be empty or trivial
     * 
     * @param issue The issue to check
     * @return true if the issue appears to be a functional story
     */
    private boolean isFunctionalStory(GitHubIssue issue) {
        // Check labels for non-story types
        for (String label : issue.getLabels()) {
            String lowerLabel = label.toLowerCase();
            if (lowerLabel.contains("epic") || 
                lowerLabel.contains("task") || 
                lowerLabel.contains("bug")) {
                return false;
            }
        }
        
        // Check body is not empty or trivial
        String body = issue.getBody();
        if (body == null || body.trim().isEmpty() || body.trim().length() < 20) {
            return false;
        }
        
        // Check for story-like language patterns
        String lowerBody = body.toLowerCase();
        boolean hasStoryIndicators = 
            lowerBody.contains("as a") || 
            lowerBody.contains("i want") || 
            lowerBody.contains("so that") ||
            lowerBody.contains("user story") ||
            lowerBody.contains("acceptance criteria");
        
        return hasStoryIndicators;
    }
}
