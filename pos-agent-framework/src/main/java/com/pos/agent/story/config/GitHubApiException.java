package com.pos.agent.story.config;

/**
 * Exception thrown when GitHub API operations fail.
 * Standalone class following inner-classes-rule.md guidelines.
 * 
 * Requirements: 2.1, 2.2, 2.3, 2.4
 */
public class GitHubApiException extends Exception {
    
    /**
     * Creates a new GitHubApiException with the specified message.
     * 
     * @param message Error message
     */
    public GitHubApiException(String message) {
        super(message);
    }
    
    /**
     * Creates a new GitHubApiException with the specified message and cause.
     * 
     * @param message Error message
     * @param cause Underlying cause
     */
    public GitHubApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
