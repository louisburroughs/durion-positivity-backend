package com.pos.agent.context;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;

/**
 * Context information for agent operations.
 * Provides environment and configuration details for agent execution.
 * 
 * This is an abstract base class - concrete implementations should extend this
 * and provide their own Builder that extends AgentContext.Builder<T>.
 */
public abstract class AgentContext {
    private final String contextId;
    private final String sessionId;
    private final Instant createdAt;
    private Instant lastUpdated;
    private final String contextType;
    private final String agentDomain;
    private final String description;
    private final Map<String, Object> properties;

    protected AgentContext(Builder<?> builder) {
        this.contextId = builder.contextId != null ? builder.contextId : "context-" + System.currentTimeMillis();
        this.sessionId = builder.sessionId != null ? builder.sessionId : "session-" + System.currentTimeMillis();
        this.createdAt = builder.createdAt != null ? builder.createdAt : Instant.now();
        this.lastUpdated = builder.lastUpdated != null ? builder.lastUpdated : this.createdAt;
        this.contextType = builder.contextType;
        this.agentDomain = builder.agentDomain;
        this.properties = builder.properties;
        this.description = builder.description;
    }

    public String getContextId() {
        return contextId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public String getContextType() {
        return contextType;
    }

    public String getAgentDomain() {
        return agentDomain;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public Object getProperty(String key) {
        return properties.get(key);
    }

    protected void updateTimestamp() {
        this.lastUpdated = Instant.now();
    }

    public String getDescription() {
        return description;
    }

    /**
     * Generic builder for AgentContext subclasses.
     * Uses the self-bounded generic pattern to enable fluent method chaining
     * while maintaining type safety across inheritance hierarchies.
     * 
     * @param <T> The concrete builder type
     */
    public static class Builder<T extends Builder<T>> {
        public String description;
        private String contextType;
        private String agentDomain;
        private Map<String, Object> properties = new HashMap<>();
        private String contextId;
        private String sessionId;
        private Instant createdAt;
        private Instant lastUpdated;

        @SuppressWarnings("unchecked")
        protected T self() {
            return (T) this;
        }

        protected T contextType(String contextType) {
            this.contextType = contextType;
            return self();
        }

        protected T type(String type) {
            this.contextType = type;
            return self();
        }

        protected T agentDomain(String agentDomain) {
            this.agentDomain = agentDomain;
            return self();
        }

        public T description(String description) {
            this.description = description;
            return self();
        }

        public T property(String key, Object value) {
            this.properties.put(key, value);
            return self();
        }

        public T properties(Map<String, Object> properties) {
            this.properties.putAll(properties);
            return self();
        }

        // Security-related methods
        public T requiresAuthentication(boolean requiresAuthentication) {
            this.properties.put("requiresAuthentication", requiresAuthentication);
            return self();
        }

        public T requiresTLS13(boolean requiresTLS13) {
            this.properties.put("requiresTLS13", requiresTLS13);
            return self();
        }

        public T requiresAuditTrail(boolean requiresAuditTrail) {
            this.properties.put("requiresAuditTrail", requiresAuditTrail);
            return self();
        }

        public T requiresAdminRole(boolean requiresAdminRole) {
            this.properties.put("requiresAdminRole", requiresAdminRole);
            return self();
        }

        public T requiredPermission(String requiredPermission) {
            this.properties.put("requiredPermission", requiredPermission);
            return self();
        }

        // Service-related methods
        public T serviceType(String serviceType) {
            this.properties.put("serviceType", serviceType);
            return self();
        }

        public T requestId(String requestId) {
            this.properties.put("requestId", requestId);
            return self();
        }

        public T secretsProvider(String secretsProvider) {
            this.properties.put("secretsProvider", secretsProvider);
            return self();
        }
    }
}
