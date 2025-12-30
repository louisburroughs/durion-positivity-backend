package com.pos.agent.context;

import java.util.Map;
import java.util.HashMap;

/**
 * Context information for agent operations.
 * Provides environment and configuration details for agent execution.
 */
public class AgentContext {
    private final String contextType;
    private final String domain;
    private final Map<String, Object> properties;

    private AgentContext(Builder builder) {
        this.contextType = builder.contextType;
        this.domain = builder.domain;
        this.properties = builder.properties;
    }

    public String getContextType() {
        return contextType;
    }

    public String getDomain() {
        return domain;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public Object getProperty(String key) {
        return properties.get(key);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String contextType;
        private String domain;
        private Map<String, Object> properties = new HashMap<>();

        public Builder contextType(String contextType) {
            this.contextType = contextType;
            return this;
        }

        public Builder type(String type) {
            this.contextType = type;
            return this;
        }

        public Builder domain(String domain) {
            this.domain = domain;
            return this;
        }

        public Builder property(String key, Object value) {
            this.properties.put(key, value);
            return this;
        }

        public Builder properties(Map<String, Object> properties) {
            this.properties.putAll(properties);
            return this;
        }

        // Security-related methods
        public Builder requiresAuthentication(boolean requiresAuthentication) {
            this.properties.put("requiresAuthentication", requiresAuthentication);
            return this;
        }

        public Builder requiresTLS13(boolean requiresTLS13) {
            this.properties.put("requiresTLS13", requiresTLS13);
            return this;
        }

        public Builder requiresAuditTrail(boolean requiresAuditTrail) {
            this.properties.put("requiresAuditTrail", requiresAuditTrail);
            return this;
        }

        public Builder requiresAdminRole(boolean requiresAdminRole) {
            this.properties.put("requiresAdminRole", requiresAdminRole);
            return this;
        }

        public Builder requiredPermission(String requiredPermission) {
            this.properties.put("requiredPermission", requiredPermission);
            return this;
        }

        // Service-related methods
        public Builder serviceType(String serviceType) {
            this.properties.put("serviceType", serviceType);
            return this;
        }

        public Builder requestId(String requestId) {
            this.properties.put("requestId", requestId);
            return this;
        }

        public Builder secretsProvider(String secretsProvider) {
            this.properties.put("secretsProvider", secretsProvider);
            return this;
        }

        public AgentContext build() {
            return new AgentContext(this);
        }
    }
}
