package com.pos.agent.context;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Context model for security posture and governance.
 * Tracks controls, policies, standards, threats, and mitigations relevant to
 * agent operations.
 */
public class SecurityContext extends AgentContext {

    private final Set<String> controls;
    private final Set<String> policies;
    private final Set<String> standards;
    private final Map<String, String> threats;
    private final Map<String, String> mitigations;
    private final Set<String> authenticationMethods;
    private final Set<String> encryptionStandards;
    private final Set<String> complianceRequirements;
    private final Map<String, String> auditFindings;
    private final String riskLevel;

    private SecurityContext(Builder builder) {
        super(builder);
        this.controls = builder.controls;
        this.policies = builder.policies;
        this.standards = builder.standards;
        this.threats = builder.threats;
        this.mitigations = builder.mitigations;
        this.authenticationMethods = builder.authenticationMethods;
        this.encryptionStandards = builder.encryptionStandards;
        this.complianceRequirements = builder.complianceRequirements;
        this.auditFindings = builder.auditFindings;
        this.riskLevel = builder.riskLevel;
    }

   
    public static Builder builder() {
        return new Builder();
    }

    public Set<String> getControls() {
        return new LinkedHashSet<>(controls);
    }

    public Set<String> getPolicies() {
        return new LinkedHashSet<>(policies);
    }

    public Set<String> getStandards() {
        return new LinkedHashSet<>(standards);
    }

    public Map<String, String> getThreats() {
        return new HashMap<>(threats);
    }

    public Map<String, String> getMitigations() {
        return new HashMap<>(mitigations);
    }

    public Set<String> getAuthenticationMethods() {
        return new LinkedHashSet<>(authenticationMethods);
    }

    public Set<String> getEncryptionStandards() {
        return new LinkedHashSet<>(encryptionStandards);
    }

    public Set<String> getComplianceRequirements() {
        return new LinkedHashSet<>(complianceRequirements);
    }

    public Map<String, String> getAuditFindings() {
        return new HashMap<>(auditFindings);
    }

    public String getRiskLevel() {
        return riskLevel;
    }

    // Mutators
    public void addControl(String control) {
        if (control != null && this.controls.add(control)) {
            updateTimestamp();
        }
    }

    public void addPolicy(String policy) {
        if (policy != null && this.policies.add(policy)) {
            updateTimestamp();
        }
    }

    public void addStandard(String standard) {
        if (standard != null && this.standards.add(standard)) {
            updateTimestamp();
        }
    }

    public void addThreat(String threat, String risk) {
        if (threat != null) {
            this.threats.put(threat, risk);
            updateTimestamp();
        }
    }

    public void addMitigation(String control, String detail) {
        if (control != null) {
            this.mitigations.put(control, detail);
            updateTimestamp();
        }
    }

    public void addAuthenticationMethod(String method) {
        if (method != null && this.authenticationMethods.add(method)) {
            updateTimestamp();
        }
    }

    public void addEncryptionStandard(String standard) {
        if (standard != null && this.encryptionStandards.add(standard)) {
            updateTimestamp();
        }
    }

    public void addComplianceRequirement(String requirement) {
        if (requirement != null && this.complianceRequirements.add(requirement)) {
            updateTimestamp();
        }
    }

    public void addAuditFinding(String findingId, String severity) {
        if (findingId != null) {
            this.auditFindings.put(findingId, severity);
            updateTimestamp();
        }
    }

    public boolean isValid() {
        return !controls.isEmpty() || !policies.isEmpty() || !standards.isEmpty();
    }

    public boolean isHardened() {
        return !controls.isEmpty() && !authenticationMethods.isEmpty() && !encryptionStandards.isEmpty();
    }

    @Override
    public String toString() {
        return "SecurityContext{" +
                "contextId='" + getContextId() + '\'' +
                ", sessionId='" + getSessionId() + '\'' +
                ", controls=" + controls.size() +
                ", policies=" + policies.size() +
                ", standards=" + standards.size() +
                ", riskLevel='" + riskLevel + '\'' +
                '}';
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        private Set<String> controls = new LinkedHashSet<>();
        private Set<String> policies = new LinkedHashSet<>();
        private Set<String> standards = new LinkedHashSet<>();
        private Map<String, String> threats = new HashMap<>();
        private Map<String, String> mitigations = new HashMap<>();
        private Set<String> authenticationMethods = new LinkedHashSet<>();
        private Set<String> encryptionStandards = new LinkedHashSet<>();
        private Set<String> complianceRequirements = new LinkedHashSet<>();
        private Map<String, String> auditFindings = new HashMap<>();
        private String riskLevel = "medium";

        public Builder() {
            domain("security");
            contextType("security-context");
        }

        public Builder riskLevel(String riskLevel) {
            if (riskLevel != null) {
                this.riskLevel = riskLevel;
            }
            return this;
        }

        public Builder addControl(String control) {
            if (control != null) {
                controls.add(control);
            }
            return this;
        }

        public Builder controls(Set<String> controls) {
            if (controls != null) {
                this.controls.addAll(controls);
            }
            return this;
        }

        public Builder addPolicy(String policy) {
            if (policy != null) {
                policies.add(policy);
            }
            return this;
        }

        public Builder policies(Set<String> policies) {
            if (policies != null) {
                this.policies.addAll(policies);
            }
            return this;
        }

        public Builder addStandard(String standard) {
            if (standard != null) {
                standards.add(standard);
            }
            return this;
        }

        public Builder standards(Set<String> standards) {
            if (standards != null) {
                this.standards.addAll(standards);
            }
            return this;
        }

        public Builder addThreat(String threat, String risk) {
            if (threat != null) {
                threats.put(threat, risk);
            }
            return this;
        }

        public Builder threats(Map<String, String> threats) {
            if (threats != null) {
                this.threats.putAll(threats);
            }
            return this;
        }

        public Builder addMitigation(String control, String detail) {
            if (control != null) {
                mitigations.put(control, detail);
            }
            return this;
        }

        public Builder mitigations(Map<String, String> mitigations) {
            if (mitigations != null) {
                this.mitigations.putAll(mitigations);
            }
            return this;
        }

        public Builder addAuthenticationMethod(String method) {
            if (method != null) {
                authenticationMethods.add(method);
            }
            return this;
        }

        public Builder authenticationMethods(Set<String> methods) {
            if (methods != null) {
                this.authenticationMethods.addAll(methods);
            }
            return this;
        }

        public Builder addEncryptionStandard(String standard) {
            if (standard != null) {
                encryptionStandards.add(standard);
            }
            return this;
        }

        public Builder encryptionStandards(Set<String> standards) {
            if (standards != null) {
                this.encryptionStandards.addAll(standards);
            }
            return this;
        }

        public Builder addComplianceRequirement(String requirement) {
            if (requirement != null) {
                complianceRequirements.add(requirement);
            }
            return this;
        }

        public Builder complianceRequirements(Set<String> requirements) {
            if (requirements != null) {
                this.complianceRequirements.addAll(requirements);
            }
            return this;
        }

        public Builder addAuditFinding(String findingId, String severity) {
            if (findingId != null) {
                auditFindings.put(findingId, severity);
            }
            return this;
        }

        public Builder auditFindings(Map<String, String> findings) {
            if (findings != null) {
                this.auditFindings.putAll(findings);
            }
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public SecurityContext build() {
            return new SecurityContext(this);
        }
    }
}
