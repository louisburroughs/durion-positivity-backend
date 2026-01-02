package com.pos.agent.core;

import java.util.ArrayList;

import java.util.List;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.DefaultContext;

/**
 * Response object for agent processing.
 * Part of frozen contract specification (REQ-016).
 * This class maintains backward compatibility with setter-based construction
 * while providing an immutable builder pattern and record-like semantics.
 */
public class AgentResponse {
    // Core fields
    private String responseId;
    private String requestId;
    private AgentProcessingState status;
    private String output;
    private double confidence;
    private List<String> recommendations;
    private String escalationReason;
    private AgentContext context;

    // Enhanced security fields
    private boolean success;
    private String errorMessage;

    // Performance tracking fields
    private long processingTimeMs;

    // Default constructor for backward compatibility
    public AgentResponse() {
    }

    // Private constructor for builder
    private AgentResponse(Builder builder) {
        this.status = builder.status;
        this.output = builder.output;
        this.confidence = builder.confidence;
        this.recommendations = builder.recommendations;
        this.escalationReason = builder.escalationReason;
        this.context = builder.context;
        this.success = builder.success;
        this.errorMessage = builder.errorMessage;
        this.processingTimeMs = builder.processingTimeMs;
        this.responseId = java.util.UUID.randomUUID().toString();
        this.requestId = builder.context != null ? builder.context.getContextId() : null;
    }

    // Getters with backward compatibility
    /**
     * Get status as enum
     * 
     * @return status as AgentStatus enum
     */
    public AgentProcessingState getStatusEnum() {
        return status;
    }

    /**
     * Get status as string for backward compatibility
     * 
     * @return status as string
     */
    public String getStatus() {
        return status != null ? status.name() : null;
    }

    /**
     * Creates a failure response with error message
     */
    public static AgentResponse failure(String errorMessage) {
        return AgentResponse.builder()
                .status(AgentProcessingState.FAILURE)
                .errorMessage(errorMessage)
                .success(false)
                .build();
    }

    /**
     * Creates a failure response with error message
     */
    public static AgentResponse success(String successMessage, double confidence) {
        return AgentResponse.builder()
                .status(AgentProcessingState.SUCCESS)
                .confidence(confidence)
                .output(successMessage)
                .success(true)
                .build();
    }

    public String getResponseId() {
        return responseId;
    }

    public String getRequestId() {
        return requestId;
    }

    public double getConfidence() {
        return confidence;
    }

    public List<String> getRecommendations() {
        return recommendations;
    }

    public String getEscalationReason() {
        return escalationReason;
    }

    public String getOutput() {
        return output;
    }

    public AgentContext getContext() {
        return context;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    // Setters for backward compatibility
    public void setStatus(String status) {
        if (status != null) {
            try {
                this.status = AgentProcessingState.valueOf(status);
            } catch (IllegalArgumentException e) {
                // If string doesn't match enum, default to FAILURE
                this.status = AgentProcessingState.FAILURE;
            }
        }
    }

    public void setStatus(AgentProcessingState status) {
        this.status = status;
    }

    public void setOutput(String output) {
        this.output = output;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public void setRecommendations(List<String> recommendations) {
        this.recommendations = recommendations;
    }

    public void setEscalationReason(String escalationReason) {
        this.escalationReason = escalationReason;
    }

    public void setContext(AgentContext context) {
        this.context = context;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setProcessingTimeMs(long processingTimeMs) {
        this.processingTimeMs = processingTimeMs;
    }

    // Builder pattern for new usage
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder class for AgentResponse
     */
    public static class Builder {
        private AgentProcessingState status;
        private String output = "";
        private double confidence;
        private List<String> recommendations = new ArrayList<>();
        private String escalationReason;
        private AgentContext context;
        private boolean success;
        private String errorMessage;
        private long processingTimeMs;

        public Builder status(AgentProcessingState status) {
            this.status = status;
            return this;
        }

        /**
         * Set status from string for backward compatibility
         * 
         * @param status status as string
         * @return builder
         */
        public Builder status(String status) {
            if (status != null) {
                try {
                    this.status = AgentProcessingState.valueOf(status.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // Default to FAILURE if unknown status
                    this.status = AgentProcessingState.FAILURE;
                }
            }
            return this;
        }

        public Builder output(String output) {
            this.output = output != null ? output : "";
            return this;
        }

        public Builder confidence(double confidence) {
            this.confidence = confidence;
            return this;
        }

        public Builder recommendations(List<String> recommendations) {
            this.recommendations = recommendations != null ? new ArrayList<>(recommendations) : new ArrayList<>();
            return this;
        }

        public Builder context(AgentContext context) {
            this.context = context != null ? context : DefaultContext.builder().build();
            return this;
        }

        // Backward compatibility methods
        public Builder success(boolean success) {
            this.success = success;
            this.status = success ? AgentProcessingState.SUCCESS : AgentProcessingState.FAILURE;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
            if (errorMessage != null && !errorMessage.isEmpty()) {
                this.output = errorMessage;
                this.status = AgentProcessingState.FAILURE;
            }
            return this;
        }


        public Builder processingTimeMs(long processingTimeMs) {
            this.processingTimeMs = processingTimeMs;
            return this;
        }

        public Builder escalationReason(String escalationReason) {
            this.escalationReason = escalationReason;
            return this;
        }

        public AgentResponse build() {
            return new AgentResponse(this);
        }
    }

    public double getAgentId() {
        if (context != null) {
            return context.getContextId().hashCode();
        }
        return -1;
    }
}