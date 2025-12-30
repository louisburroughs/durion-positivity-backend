package com.pos.agent.core;

import java.util.List;
import java.util.Map;

/**
 * Response object for agent processing
 * Part of frozen contract specification (REQ-016)
 * Enhanced with security features and builder pattern while maintaining
 * backward compatibility
 */
public class AgentResponse {
    // Original fields for backward compatibility
    private String status;
    private String output;
    private double confidence;
    private List<String> recommendations;
    private String escalationReason;
    private Map<String, Object> context;

    // Enhanced security fields
    private boolean success;
    private String errorMessage;
    private AgentManager.SecurityValidation securityValidation;

    // Performance tracking fields
    private long processingTimeMs;

    // Constructors
    public AgentResponse() {
        // Default constructor for backward compatibility
    }

    private AgentResponse(Builder builder) {
        this.status = builder.status;
        this.output = builder.output;
        this.confidence = builder.confidence;
        this.recommendations = builder.recommendations;
        this.escalationReason = builder.escalationReason;
        this.context = builder.context;
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
        this.securityValidation = builder.securityValidation;
        this.processingTimeMs = builder.processingTimeMs;
    }

    // Original getters/setters for backward compatibility
    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public List<String> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<String> recommendations) {
        this.recommendations = recommendations;
    }

    public String getEscalationReason() {
        return escalationReason;
    }

    public void setEscalationReason(String escalationReason) {
        this.escalationReason = escalationReason;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    // Enhanced getters/setters for security features
    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public AgentManager.SecurityValidation getSecurityValidation() {
        return securityValidation;
    }

    public void setSecurityValidation(AgentManager.SecurityValidation securityValidation) {
        this.securityValidation = securityValidation;
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    public void setProcessingTimeMs(long processingTimeMs) {
        this.processingTimeMs = processingTimeMs;
    }

    // Builder pattern for advanced usage
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String status;
        private String output;
        private double confidence;
        private List<String> recommendations;
        private String escalationReason;
        private Map<String, Object> context;
        private boolean success;
        private String errorMessage;
        private AgentManager.SecurityValidation securityValidation;
        private long processingTimeMs;

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder output(String output) {
            this.output = output;
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder recommendations(List<String> recommendations) {
            this.recommendations = recommendations;
            return this;
        }

        public Builder escalationReason(String escalationReason) {
            this.escalationReason = escalationReason;
            return this;
        }

        public Builder context(Map<String, Object> context) {
            this.context = context;
            return this;
        }

        public Builder success(boolean success) {
            this.success = success;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            return this;
        }

        public Builder securityValidation(AgentManager.SecurityValidation securityValidation) {
            this.securityValidation = securityValidation;
            return this;
        }

        public Builder processingTimeMs(long processingTimeMs) {
            this.processingTimeMs = processingTimeMs;
            return this;
        }

        public AgentResponse build() {
            return new AgentResponse(this);
        }
    }
}
