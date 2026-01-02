package com.pos.agent.core;

import com.pos.agent.context.AgentContext;

/**
 * Request object for agent processing
 * Part of frozen contract specification (REQ-016)
 * Enhanced with security features and builder pattern while maintaining
 * backward compatibility
 */
public class AgentRequest {

    private Priority priority;

    // Enhanced security fields
    private AgentContext agentContext;
    private SecurityContext securityContext;
    private boolean requireTLS13;

    public enum Priority {
        LOW, NORMAL, HIGH, URGENT
    }

    // Constructors
    private AgentRequest() {
        // Default constructor for backward compatibility
    }

    private AgentRequest(Builder builder) {
        this.agentContext = builder.agentContext;
        this.securityContext = builder.securityContext;
        this.requireTLS13 = builder.requireTLS13;
    }

    public Priority getPriority() {
        return priority;
    }

    public void setPriority(Priority priority) {
        this.priority = priority;
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
        // private String type;
        // private String description;
        private Priority priority;
        private AgentContext agentContext;
        private SecurityContext securityContext;
        private boolean requireTLS13;

        public Builder priority(Priority priority) {
            this.priority = priority;
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
            if (agentContext == null) {
                throw new IllegalStateException("agentContext is required and cannot be null");
            }
            if (securityContext == null) {
                throw new IllegalStateException("securityContext is required and cannot be null");
            }

            return new AgentRequest(this);
        }
    }
}
