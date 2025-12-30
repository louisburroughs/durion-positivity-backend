package com.pos.agent.core;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Response object for agent processing.
 * Part of frozen contract specification (REQ-016).
 * This class maintains backward compatibility with setter-based construction
 * while providing an immutable builder pattern and record-like semantics.
 */
public class AgentResponse {
    // Core fields
    private AgentStatus status;
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
        this.securityValidation = builder.securityValidation;
        this.processingTimeMs = builder.processingTimeMs;
    }

    // Getters with backward compatibility
    /**
     * Get status as enum
     * @return status as AgentStatus enum
     */
    public AgentStatus getStatusEnum() {
        return status;
    }
    
    /**
     * Get status as string for backward compatibility
     * @return status as string
     */
    public String getStatus() {
        return status != null ? status.name() : null;
    }

    /**
     * Creates a failure response with error message
     */
    public static AgentResponse failure(String errorMessage) {
        return new AgentResponse(AgentStatus.FAILURE, errorMessage, 0.0, 
                               Collections.emptyList(), Collections.emptyMap());
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

    public Map<String, Object> getContext() {
        return context;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public AgentManager.SecurityValidation getSecurityValidation() {
        return securityValidation;
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    // Setters for backward compatibility
    public void setStatus(String status) {
        if (status != null) {
            try {
                this.status = AgentStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                // If string doesn't match enum, default to FAILURE
                this.status = AgentStatus.FAILURE;
            }
        }
    }
    
    public void setStatus(AgentStatus status) {
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

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setSecurityValidation(AgentManager.SecurityValidation securityValidation) {
        this.securityValidation = securityValidation;
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
        private AgentStatus status;
        private String output;
        private double confidence;
        private List<String> recommendations;
        private String escalationReason;
        private Map<String, Object> context;
        private boolean success;
        private String errorMessage;
        private AgentManager.SecurityValidation securityValidation;
        private long processingTimeMs;

        public Builder status(AgentStatus status) {
            this.status = status;
            return this;
        }
        
        /**
         * Set status from string for backward compatibility
         * @param status status as string
         * @return builder
         */
        public Builder status(String status) {
            if (status != null) {
                try {
                    this.status = AgentStatus.valueOf(status);
                } catch (IllegalArgumentException e) {
                    // If string doesn't match enum, default to FAILURE
                    this.status = AgentStatus.FAILURE;
                }
            }
            return this;
        }

        public Builder status(String status) {
            // Backward compatibility: convert string to enum
            if (status != null) {
                try {
                    this.status = AgentStatus.valueOf(status.toUpperCase());
                } catch (IllegalArgumentException e) {
                    // Default to SUCCESS if unknown status
                    this.status = AgentStatus.SUCCESS;
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

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
            return this;
        }

        // Backward compatibility methods
        public Builder success(boolean success) {
            this.status = success ? AgentStatus.SUCCESS : AgentStatus.FAILURE;
            return this;
        }

        public Builder errorMessage(String errorMessage) {
            if (errorMessage != null && !errorMessage.isEmpty()) {
                this.output = errorMessage;
                this.status = AgentStatus.FAILURE;
                this.metadata.put("errorMessage", errorMessage);
            }
            return this;
        }

        public Builder securityValidation(AgentManager.SecurityValidation securityValidation) {
            if (securityValidation != null) {
                this.metadata.put("securityValidation", securityValidation);
            }
            return this;
        }

        public Builder processingTimeMs(long processingTimeMs) {
            this.metadata.put("processingTimeMs", processingTimeMs);
            return this;
        }

        public Builder escalationReason(String escalationReason) {
            if (escalationReason != null) {
                this.metadata.put("escalationReason", escalationReason);
            }
            return this;
        }

        public Builder context(Map<String, Object> context) {
            if (context != null) {
                this.metadata.put("context", context);
            }
            return this;
        }

        public AgentResponse build() {
            return new AgentResponse(status, output, confidence, recommendations, metadata);
        }
    }
}

