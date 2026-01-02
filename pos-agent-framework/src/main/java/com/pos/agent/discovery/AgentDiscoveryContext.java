package com.pos.agent.discovery;

import com.pos.agent.context.AgentContext;
import com.pos.agent.core.AgentRequest;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable context extracted from AgentRequest for discovery decisions.
 * Provides convenient access to request properties and metadata.
 */
public class AgentDiscoveryContext {

    private final String domain;
    private final String objective;
    private final Map<String, Object> properties;
    private final String sessionId;
    private final int confidenceThreshold;

    private AgentDiscoveryContext(Builder builder) {
        this.domain = Objects.requireNonNull(builder.domain, "domain is required");
        this.objective = builder.objective;
        this.properties = new HashMap<>(builder.properties);
        this.sessionId = builder.sessionId;
        this.confidenceThreshold = builder.confidenceThreshold;
    }

    // Getters
    public String getDomain() {
        return domain;
    }

    public Optional<String> getObjective() {
        return Optional.ofNullable(objective);
    }

    public Map<String, Object> getProperties() {
        return new HashMap<>(properties);
    }

    public Optional<String> getSessionId() {
        return Optional.ofNullable(sessionId);
    }

    public int getConfidenceThreshold() {
        return confidenceThreshold;
    }

    /**
     * Safely retrieves a property value with type conversion.
     */
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getProperty(String key, Class<T> type) {
        Object value = properties.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (type.isAssignableFrom(value.getClass())) {
            return Optional.of((T) value);
        }
        return Optional.empty();
    }

    /**
     * Extracts discovery context from an AgentRequest.
     */
    public static AgentDiscoveryContext fromRequest(AgentRequest request) {
        AgentContext agentContext = request.getAgentContext();

        Builder builder = new Builder(agentContext.getAgentDomain())
                .sessionId(agentContext.getSessionId())
                .properties(agentContext.getProperties());

        // Extract objective from properties if available
        Object objectiveValue = agentContext.getProperties().get("objective");
        if (objectiveValue != null) {
            builder.objective(objectiveValue.toString());
        }

        return builder.build();
    }

    // Builder
    public static class Builder {
        private final String domain;
        private String objective;
        private Map<String, Object> properties = new HashMap<>();
        private String sessionId;
        private int confidenceThreshold = 70;

        public Builder(String domain) {
            this.domain = Objects.requireNonNull(domain);
        }

        public Builder objective(String objective) {
            this.objective = objective;
            return this;
        }

        public Builder properties(Map<String, Object> properties) {
            this.properties = new HashMap<>(properties);
            return this;
        }

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder confidenceThreshold(int threshold) {
            this.confidenceThreshold = threshold;
            return this;
        }

        public AgentDiscoveryContext build() {
            return new AgentDiscoveryContext(this);
        }
    }
}
