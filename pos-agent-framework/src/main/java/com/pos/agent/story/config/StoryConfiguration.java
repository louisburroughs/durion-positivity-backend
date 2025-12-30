package com.pos.agent.story.config;

import java.util.Objects;
import java.util.Optional;

/**
 * Configuration settings for the Story Strengthening Agent.
 * Includes validation rules, thresholds, and operational parameters.
 * 
 * Requirements: 1.1, 1.2, 11.2, 11.3
 */
public class StoryConfiguration {
    private final String allowedRepository;
    private final String requiredIssuePrefix;
    private final int maxRewriteIterations;
    private final int maxAcceptanceCriteria;
    private final int maxOpenQuestions;
    private final boolean enableLoopDetection;

    private StoryConfiguration(Builder builder) {
        this.allowedRepository = Objects.requireNonNull(builder.allowedRepository, "Allowed repository cannot be null");
        this.requiredIssuePrefix = Objects.requireNonNull(builder.requiredIssuePrefix, "Required issue prefix cannot be null");
        this.maxRewriteIterations = builder.maxRewriteIterations;
        this.maxAcceptanceCriteria = builder.maxAcceptanceCriteria;
        this.maxOpenQuestions = builder.maxOpenQuestions;
        this.enableLoopDetection = builder.enableLoopDetection;
    }

    public String getAllowedRepository() {
        return allowedRepository;
    }

    public String getRequiredIssuePrefix() {
        return requiredIssuePrefix;
    }

    public int getMaxRewriteIterations() {
        return maxRewriteIterations;
    }

    public int getMaxAcceptanceCriteria() {
        return maxAcceptanceCriteria;
    }

    public int getMaxOpenQuestions() {
        return maxOpenQuestions;
    }

    public boolean isEnableLoopDetection() {
        return enableLoopDetection;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String allowedRepository = "durion-positivity-backend";
        private String requiredIssuePrefix = "[BACKEND] [STORY]";
        private int maxRewriteIterations = 2;
        private int maxAcceptanceCriteria = 25;
        private int maxOpenQuestions = 10;
        private boolean enableLoopDetection = true;

        public Builder allowedRepository(String allowedRepository) {
            this.allowedRepository = allowedRepository;
            return this;
        }

        public Builder requiredIssuePrefix(String requiredIssuePrefix) {
            this.requiredIssuePrefix = requiredIssuePrefix;
            return this;
        }

        public Builder maxRewriteIterations(int maxRewriteIterations) {
            this.maxRewriteIterations = maxRewriteIterations;
            return this;
        }

        public Builder maxAcceptanceCriteria(int maxAcceptanceCriteria) {
            this.maxAcceptanceCriteria = maxAcceptanceCriteria;
            return this;
        }

        public Builder maxOpenQuestions(int maxOpenQuestions) {
            this.maxOpenQuestions = maxOpenQuestions;
            return this;
        }

        public Builder enableLoopDetection(boolean enableLoopDetection) {
            this.enableLoopDetection = enableLoopDetection;
            return this;
        }

        public StoryConfiguration build() {
            return new StoryConfiguration(this);
        }
    }

    @Override
    public String toString() {
        return String.format("StoryConfiguration{repo='%s', prefix='%s', maxIterations=%d, maxScenarios=%d, maxQuestions=%d}", 
                           allowedRepository, requiredIssuePrefix, maxRewriteIterations, 
                           maxAcceptanceCriteria, maxOpenQuestions);
    }
}
