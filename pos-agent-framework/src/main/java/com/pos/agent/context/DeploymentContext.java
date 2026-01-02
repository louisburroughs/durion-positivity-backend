package com.pos.agent.context;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Context model for deployment planning and execution.
 * Captures artifacts, strategies, environments, and approvals.
 */
public class DeploymentContext extends AgentContext {

    private final Set<String> artifacts;
    private final Map<String, String> artifactVersions;
    private final Set<String> environments;
    private final Set<String> deploymentTargets;
    private final Map<String, String> targetStrategies;
    private final Set<String> approvals;
    private final Set<String> maintenanceWindows;

    private DeploymentContext(Builder builder) {
        super(builder);
        this.artifacts = builder.artifacts;
        this.artifactVersions = builder.artifactVersions;
        this.environments = builder.environments;
        this.deploymentTargets = builder.deploymentTargets;
        this.targetStrategies = builder.targetStrategies;
        this.approvals = builder.approvals;
        this.maintenanceWindows = builder.maintenanceWindows;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Set<String> getArtifacts() {
        return new LinkedHashSet<>(artifacts);
    }

    public Map<String, String> getArtifactVersions() {
        return new HashMap<>(artifactVersions);
    }

    public Set<String> getEnvironments() {
        return new LinkedHashSet<>(environments);
    }

    public Set<String> getDeploymentTargets() {
        return new LinkedHashSet<>(deploymentTargets);
    }

    public Map<String, String> getTargetStrategies() {
        return new HashMap<>(targetStrategies);
    }

    public Set<String> getApprovals() {
        return new LinkedHashSet<>(approvals);
    }

    public Set<String> getMaintenanceWindows() {
        return new LinkedHashSet<>(maintenanceWindows);
    }

    // Mutators
    public void addArtifact(String artifact, String version) {
        if (artifact != null) {
            this.artifacts.add(artifact);
            if (version != null) {
                this.artifactVersions.put(artifact, version);
            }
            updateTimestamp();
        }
    }

    public void addEnvironment(String environment) {
        if (environment != null && this.environments.add(environment)) {
            updateTimestamp();
        }
    }

    public void addDeploymentTarget(String target, String strategy) {
        if (target != null) {
            this.deploymentTargets.add(target);
            if (strategy != null) {
                this.targetStrategies.put(target, strategy);
            }
            updateTimestamp();
        }
    }

    public void addApproval(String approval) {
        if (approval != null && this.approvals.add(approval)) {
            updateTimestamp();
        }
    }

    public void addMaintenanceWindow(String window) {
        if (window != null && this.maintenanceWindows.add(window)) {
            updateTimestamp();
        }
    }

    public void updateArtifactVersion(String artifact, String version) {
        if (artifact != null && version != null && this.artifacts.contains(artifact)) {
            this.artifactVersions.put(artifact, version);
            updateTimestamp();
        }
    }

    public void updateTargetStrategy(String target, String strategy) {
        if (target != null && strategy != null && this.deploymentTargets.contains(target)) {
            this.targetStrategies.put(target, strategy);
            updateTimestamp();
        }
    }

    // Validation helpers
    public boolean isValid() {
        return !artifacts.isEmpty() || !deploymentTargets.isEmpty();
    }

    public boolean isReadyForRollout() {
        return isValid() && !approvals.isEmpty();
    }

    @Override
    public String toString() {
        return "DeploymentContext{" +
                "contextId='" + getContextId() + '\'' +
                ", sessionId='" + getSessionId() + '\'' +
                ", artifacts=" + artifacts.size() +
                ", targets=" + deploymentTargets.size() +
                '}';
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        private Set<String> artifacts = new LinkedHashSet<>();
        private Map<String, String> artifactVersions = new HashMap<>();
        private Set<String> environments = new LinkedHashSet<>();
        private Set<String> deploymentTargets = new LinkedHashSet<>();
        private Map<String, String> targetStrategies = new HashMap<>();
        private Set<String> approvals = new LinkedHashSet<>();
        private Set<String> maintenanceWindows = new LinkedHashSet<>();

        public Builder() {
            agentDomain("deployment");
            contextType("deployment-context");
        }

        public Builder addArtifact(String artifact, String version) {
            if (artifact != null) {
                artifacts.add(artifact);
                if (version != null) {
                    artifactVersions.put(artifact, version);
                }
            }
            return this;
        }

        public Builder artifacts(Set<String> artifacts) {
            if (artifacts != null) {
                this.artifacts.addAll(artifacts);
            }
            return this;
        }

        public Builder artifactVersions(Map<String, String> versions) {
            if (versions != null) {
                this.artifactVersions.putAll(versions);
            }
            return this;
        }

        public Builder addEnvironment(String environment) {
            if (environment != null) {
                environments.add(environment);
            }
            return this;
        }

        public Builder environments(Set<String> environments) {
            if (environments != null) {
                this.environments.addAll(environments);
            }
            return this;
        }

        public Builder addDeploymentTarget(String target, String strategy) {
            if (target != null) {
                deploymentTargets.add(target);
                if (strategy != null) {
                    targetStrategies.put(target, strategy);
                }
            }
            return this;
        }

        public Builder deploymentTargets(Set<String> targets) {
            if (targets != null) {
                this.deploymentTargets.addAll(targets);
            }
            return this;
        }

        public Builder targetStrategies(Map<String, String> strategies) {
            if (strategies != null) {
                this.targetStrategies.putAll(strategies);
            }
            return this;
        }

        public Builder addApproval(String approval) {
            if (approval != null) {
                approvals.add(approval);
            }
            return this;
        }

        public Builder approvals(Set<String> approvals) {
            if (approvals != null) {
                this.approvals.addAll(approvals);
            }
            return this;
        }

        public Builder addMaintenanceWindow(String window) {
            if (window != null) {
                maintenanceWindows.add(window);
            }
            return this;
        }

        public Builder maintenanceWindows(Set<String> windows) {
            if (windows != null) {
                this.maintenanceWindows.addAll(windows);
            }
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public DeploymentContext build() {
            return new DeploymentContext(this);
        }
    }
}
