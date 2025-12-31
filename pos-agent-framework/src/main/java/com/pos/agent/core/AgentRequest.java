package com.pos.agent.core;

import com.pos.agent.context.AgentContext;

/**
 * Request object for agent processing
 * Part of frozen contract specification (REQ-016)
 * Enhanced with security features and builder pattern while maintaining
 * backward compatibility
 */
public class AgentRequest {
    // Original fields for backward compatibility
    private String description;
    private Object context;
    private String type;

    // Enhanced security fields
    private AgentContext agentContext;
    private SecurityContext securityContext;
    private boolean requireTLS13;

    // Constructors
    public AgentRequest() {
        // Default constructor for backward compatibility
    }

    private AgentRequest(Builder builder) {
        this.type = builder.type;
        this.description = builder.description;
        this.context = builder.context;
        this.agentContext = builder.agentContext;
        this.securityContext = builder.securityContext;
        this.requireTLS13 = builder.requireTLS13;
    }

    // Original getters/setters for backward compatibility
    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Object getContext() {
        return context;
    }

    public void setContext(Object context) {
        this.context = context;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    // Enhanced getters/setters for security features
    public AgentContext getAgentContext() {
        return agentContext;
    }

    public void setAgentContext(AgentContext agentContext) {
        this.agentContext = agentContext;
    }

    public SecurityContext getSecurityContext() {
        return securityContext;
    }

    public void setSecurityContext(SecurityContext securityContext) {
        this.securityContext = securityContext;
    }

    public boolean isRequireTLS13() {
        return requireTLS13;
    }

    public void setRequireTLS13(boolean requireTLS13) {
        this.requireTLS13 = requireTLS13;
    }

    // Builder pattern for advanced usage
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String type;
        private String description;
        private Object context;
        private AgentContext agentContext;
        private SecurityContext securityContext;
        private boolean requireTLS13;

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder context(Object context) {
            this.context = context;
            return this;
        }

        public Builder context(AgentContext agentContext) {
            this.agentContext = agentContext;
            return this;
        }

        public Builder agentContext(AgentContext agentContext) {
            this.agentContext = agentContext;
            return this;
        }

        public Builder securityContext(SecurityContext securityContext) {
            this.securityContext = securityContext;
            return this;
        }

        public Builder requireTLS13(boolean requireTLS13) {
            this.requireTLS13 = requireTLS13;
            return this;
        }

        public AgentRequest build() {
            // Validate required fields
            if (description == null || description.trim().isEmpty()) {
                throw new IllegalStateException("description is required and cannot be null or empty");
            }
            if (type == null || type.trim().isEmpty()) {
                throw new IllegalStateException("type is required and cannot be null or empty");
            }
            if (context == null && agentContext == null) {
                throw new IllegalStateException("context or agentContext is required and cannot be null");
            }

            return new AgentRequest(this);
        }
    }
}
