package com.pos.agent.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Response object for agent processing.
 * Part of frozen contract specification (REQ-016).
 * This class maintains backward compatibility with setter-based construction
 * while providing an immutable builder pattern and record-like semantics.
 */
public final class AgentResponse {
    // Core fields
    private final AgentStatus status;
    private final String output;
    private final double confidence;
    private final List<String> recommendations;
    private final Map<String, Object> metadata;

    // Mutable state for backward compatibility (deprecated)
    private String legacyStatus;
    private String mutableOutput;
    private double mutableConfidence;
    private List<String> mutableRecommendations;
    private Map<String, Object> mutableMetadata;
    private boolean useImmutableState = false;

    /**
     * Default constructor for backward compatibility with setter pattern
     */
    public AgentResponse() {
        this.status = AgentStatus.SUCCESS;
        this.output = "";
        this.confidence = 0.0;
        this.recommendations = Collections.emptyList();
        this.metadata = Collections.emptyMap();
        this.useImmutableState = false;
        
        // Initialize mutable fields
        this.mutableOutput = "";
        this.mutableConfidence = 0.0;
        this.mutableRecommendations = new ArrayList<>();
        this.mutableMetadata = new HashMap<>();
    }

    /**
     * Private constructor for builder pattern (immutable)
     */
    private AgentResponse(AgentStatus status, String output, double confidence, 
                         List<String> recommendations, Map<String, Object> metadata) {
        this.status = status;
        this.output = output;
        this.confidence = confidence;
        this.recommendations = recommendations != null 
            ? Collections.unmodifiableList(new ArrayList<>(recommendations)) 
            : Collections.emptyList();
        this.metadata = metadata != null 
            ? Collections.unmodifiableMap(new HashMap<>(metadata)) 
            : Collections.emptyMap();
        this.useImmutableState = true;
        
        // Initialize mutable fields as null for immutable instances
        this.mutableOutput = null;
        this.mutableRecommendations = null;
        this.mutableMetadata = null;
    }

    /**
     * Builder for creating immutable AgentResponse instances
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a success response with minimal fields
     */
    public static AgentResponse success(String output, double confidence) {
        return new AgentResponse(AgentStatus.SUCCESS, output, confidence, 
                               Collections.emptyList(), Collections.emptyMap());
    }

    /**
     * Creates a failure response with error message
     */
    public static AgentResponse failure(String errorMessage) {
        return new AgentResponse(AgentStatus.FAILURE, errorMessage, 0.0, 
                               Collections.emptyList(), Collections.emptyMap());
    }

    // Getters
    public AgentStatus statusEnum() {
        return status;
    }

    public String getStatus() {
        if (useImmutableState) {
            return status.name();
        }
        return legacyStatus;
    }

    public String getOutput() {
        if (useImmutableState) {
            return output;
        }
        return mutableOutput;
    }

    public double getConfidence() {
        if (useImmutableState) {
            return confidence;
        }
        return mutableConfidence;
    }

    public List<String> getRecommendations() {
        if (useImmutableState) {
            return recommendations;
        }
        return mutableRecommendations;
    }

    public Map<String, Object> getMetadata() {
        if (useImmutableState) {
            return metadata;
        }
        return mutableMetadata;
    }

    // Backward compatibility getters
    public Map<String, Object> getContext() {
        if (useImmutableState) {
            return (Map<String, Object>) metadata.get("context");
        }
        return (Map<String, Object>) mutableMetadata.get("context");
    }

    public boolean isSuccess() {
        if (useImmutableState) {
            return status == AgentStatus.SUCCESS;
        }
        return "SUCCESS".equals(legacyStatus);
    }

    public String getErrorMessage() {
        if (useImmutableState) {
            return status == AgentStatus.FAILURE ? output : null;
        }
        return (String) mutableMetadata.get("errorMessage");
    }

    public AgentManager.SecurityValidation getSecurityValidation() {
        if (useImmutableState) {
            return (AgentManager.SecurityValidation) metadata.get("securityValidation");
        }
        return (AgentManager.SecurityValidation) mutableMetadata.get("securityValidation");
    }

    public long getProcessingTimeMs() {
        if (useImmutableState) {
            Object value = metadata.get("processingTimeMs");
            return value instanceof Long ? (Long) value : 0L;
        }
        Object value = mutableMetadata.get("processingTimeMs");
        return value instanceof Long ? (Long) value : 0L;
    }

    public String getEscalationReason() {
        if (useImmutableState) {
            return (String) metadata.get("escalationReason");
        }
        return (String) mutableMetadata.get("escalationReason");
    }

    // Setters for backward compatibility (deprecated)
    public void setStatus(String status) {
        this.legacyStatus = status;
    }

    public void setOutput(String output) {
        this.mutableOutput = output;
    }

    public void setConfidence(double confidence) {
        this.mutableConfidence = confidence;
    }

    public void setRecommendations(List<String> recommendations) {
        this.mutableRecommendations = recommendations;
    }

    public void setContext(Map<String, Object> context) {
        if (this.mutableMetadata == null) {
            this.mutableMetadata = new HashMap<>();
        }
        this.mutableMetadata.put("context", context);
    }

    public void setEscalationReason(String escalationReason) {
        if (this.mutableMetadata == null) {
            this.mutableMetadata = new HashMap<>();
        }
        this.mutableMetadata.put("escalationReason", escalationReason);
    }

    /**
     * Builder class for AgentResponse
     */
    public static class Builder {
        private AgentStatus status = AgentStatus.SUCCESS;
        private String output = "";
        private double confidence = 0.0;
        private List<String> recommendations = new ArrayList<>();
        private Map<String, Object> metadata = new HashMap<>();

        public Builder status(AgentStatus status) {
            this.status = status;
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

