package com.pos.agent.context;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Context model for architecture definitions and decisions.
 * Captures services, patterns, integrations, and constraints that shape the
 * system design.
 */
public class ArchitectureContext extends AgentContext {

    private final Set<String> services;
    private final Map<String, String> serviceOwners;
    private final Set<String> patterns;
    private final Set<String> decisions;
    private final Map<String, String> decisionRecords;
    private final Set<String> constraints;
    private final Set<String> integrations;
    private final Map<String, String> interfaces;
    private final Set<String> dataFlows;
    private final Set<String> qualityAttributes;

    private ArchitectureContext(Builder builder) {
        super(builder);
        this.services = builder.services;
        this.serviceOwners = builder.serviceOwners;
        this.patterns = builder.patterns;
        this.decisions = builder.decisions;
        this.decisionRecords = builder.decisionRecords;
        this.constraints = builder.constraints;
        this.integrations = builder.integrations;
        this.interfaces = builder.interfaces;
        this.dataFlows = builder.dataFlows;
        this.qualityAttributes = builder.qualityAttributes;
    }


    public static Builder builder() {
        return new Builder();
    }

    public Set<String> getServices() {
        return new LinkedHashSet<>(services);
    }

    public Map<String, String> getServiceOwners() {
        return new HashMap<>(serviceOwners);
    }

    public Set<String> getPatterns() {
        return new LinkedHashSet<>(patterns);
    }

    public Set<String> getDecisions() {
        return new LinkedHashSet<>(decisions);
    }

    public Map<String, String> getDecisionRecords() {
        return new HashMap<>(decisionRecords);
    }

    public Set<String> getConstraints() {
        return new LinkedHashSet<>(constraints);
    }

    public Set<String> getIntegrations() {
        return new LinkedHashSet<>(integrations);
    }

    public Map<String, String> getInterfaces() {
        return new HashMap<>(interfaces);
    }

    public Set<String> getDataFlows() {
        return new LinkedHashSet<>(dataFlows);
    }

    public Set<String> getQualityAttributes() {
        return new LinkedHashSet<>(qualityAttributes);
    }

    // Mutators
    public void addService(String service, String owner) {
        if (service != null) {
            this.services.add(service);
            if (owner != null) {
                this.serviceOwners.put(service, owner);
            }
            updateTimestamp();
        }
    }

    public void addPattern(String pattern) {
        if (pattern != null && this.patterns.add(pattern)) {
            updateTimestamp();
        }
    }

    public void addDecision(String decisionId, String summary) {
        if (decisionId != null) {
            this.decisions.add(decisionId);
            if (summary != null) {
                this.decisionRecords.put(decisionId, summary);
            }
            updateTimestamp();
        }
    }

    public void addConstraint(String constraint) {
        if (constraint != null && this.constraints.add(constraint)) {
            updateTimestamp();
        }
    }

    public void addIntegration(String integration) {
        if (integration != null && this.integrations.add(integration)) {
            updateTimestamp();
        }
    }

    public void addInterface(String name, String protocol) {
        if (name != null) {
            this.interfaces.put(name, protocol);
            updateTimestamp();
        }
    }

    public void addDataFlow(String dataFlow) {
        if (dataFlow != null && this.dataFlows.add(dataFlow)) {
            updateTimestamp();
        }
    }

    public void addQualityAttribute(String attribute) {
        if (attribute != null && this.qualityAttributes.add(attribute)) {
            updateTimestamp();
        }
    }

    public boolean isValid() {
        return !services.isEmpty() || !patterns.isEmpty() || !decisions.isEmpty();
    }

    public boolean isDocumented() {
        return !decisions.isEmpty() && !decisionRecords.isEmpty();
    }

    @Override
    public String toString() {
        return "ArchitectureContext{" +
                "contextId='" + getContextId() + '\'' +
                ", sessionId='" + getSessionId() + '\'' +
                ", services=" + services.size() +
                ", patterns=" + patterns.size() +
                ", decisions=" + decisions.size() +
                '}';
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        private Set<String> services = new LinkedHashSet<>();
        private Map<String, String> serviceOwners = new HashMap<>();
        private Set<String> patterns = new LinkedHashSet<>();
        private Set<String> decisions = new LinkedHashSet<>();
        private Map<String, String> decisionRecords = new HashMap<>();
        private Set<String> constraints = new LinkedHashSet<>();
        private Set<String> integrations = new LinkedHashSet<>();
        private Map<String, String> interfaces = new HashMap<>();
        private Set<String> dataFlows = new LinkedHashSet<>();
        private Set<String> qualityAttributes = new LinkedHashSet<>();

        public Builder() {
            domain("architecture");
            contextType("architecture-context");
        }

        public Builder addService(String service, String owner) {
            if (service != null) {
                services.add(service);
                if (owner != null) {
                    serviceOwners.put(service, owner);
                }
            }
            return this;
        }

        public Builder services(Set<String> services) {
            if (services != null) {
                this.services.addAll(services);
            }
            return this;
        }

        public Builder serviceOwners(Map<String, String> owners) {
            if (owners != null) {
                this.serviceOwners.putAll(owners);
            }
            return this;
        }

        public Builder addPattern(String pattern) {
            if (pattern != null) {
                patterns.add(pattern);
            }
            return this;
        }

        public Builder patterns(Set<String> patterns) {
            if (patterns != null) {
                this.patterns.addAll(patterns);
            }
            return this;
        }

        public Builder addDecision(String decisionId, String summary) {
            if (decisionId != null) {
                decisions.add(decisionId);
                if (summary != null) {
                    decisionRecords.put(decisionId, summary);
                }
            }
            return this;
        }

        public Builder decisions(Set<String> decisions) {
            if (decisions != null) {
                this.decisions.addAll(decisions);
            }
            return this;
        }

        public Builder decisionRecords(Map<String, String> records) {
            if (records != null) {
                this.decisionRecords.putAll(records);
            }
            return this;
        }

        public Builder addConstraint(String constraint) {
            if (constraint != null) {
                constraints.add(constraint);
            }
            return this;
        }

        public Builder constraints(Set<String> constraints) {
            if (constraints != null) {
                this.constraints.addAll(constraints);
            }
            return this;
        }

        public Builder addIntegration(String integration) {
            if (integration != null) {
                integrations.add(integration);
            }
            return this;
        }

        public Builder integrations(Set<String> integrations) {
            if (integrations != null) {
                this.integrations.addAll(integrations);
            }
            return this;
        }

        public Builder addInterface(String name, String protocol) {
            if (name != null) {
                interfaces.put(name, protocol);
            }
            return this;
        }

        public Builder interfaces(Map<String, String> interfaces) {
            if (interfaces != null) {
                this.interfaces.putAll(interfaces);
            }
            return this;
        }

        public Builder addDataFlow(String dataFlow) {
            if (dataFlow != null) {
                dataFlows.add(dataFlow);
            }
            return this;
        }

        public Builder dataFlows(Set<String> dataFlows) {
            if (dataFlows != null) {
                this.dataFlows.addAll(dataFlows);
            }
            return this;
        }

        public Builder addQualityAttribute(String attribute) {
            if (attribute != null) {
                qualityAttributes.add(attribute);
            }
            return this;
        }

        public Builder qualityAttributes(Set<String> attributes) {
            if (attributes != null) {
                this.qualityAttributes.addAll(attributes);
            }
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public ArchitectureContext build() {
            return new ArchitectureContext(this);
        }
    }
}
