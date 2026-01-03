package com.pos.agent.context;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Context model for operations and production readiness scenarios.
 * Tracks operational checks, monitoring, deployment readiness, and health
 * status.
 */
public class OperationsContext extends AgentContext {

    // Readiness checks
    private final Set<String> readinessChecks;
    private final Map<String, String> checkStatuses;
    private final Set<String> healthChecks;
    private final Map<String, String> healthStatuses;

    // Monitoring
    private final Set<String> monitoringTools;
    private final Map<String, Object> metrics;
    private final Set<String> alertRules;
    private final Set<String> dashboards;

    // Deployment
    private final Set<String> deploymentTargets;
    private final Map<String, String> deploymentConfigurations;
    private final Set<String> environments;

    private OperationsContext(Builder builder) {
        super(builder);
        this.readinessChecks = builder.readinessChecks;
        this.checkStatuses = builder.checkStatuses;
        this.healthChecks = builder.healthChecks;
        this.healthStatuses = builder.healthStatuses;

        this.monitoringTools = builder.monitoringTools;
        this.metrics = builder.metrics;
        this.alertRules = builder.alertRules;
        this.dashboards = builder.dashboards;

        this.deploymentTargets = builder.deploymentTargets;
        this.deploymentConfigurations = builder.deploymentConfigurations;
        this.environments = builder.environments;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public Set<String> getReadinessChecks() {
        return new LinkedHashSet<>(readinessChecks);
    }

    public Map<String, String> getCheckStatuses() {
        return new HashMap<>(checkStatuses);
    }

    public Set<String> getHealthChecks() {
        return new LinkedHashSet<>(healthChecks);
    }

    public Map<String, String> getHealthStatuses() {
        return new HashMap<>(healthStatuses);
    }

    public Set<String> getMonitoringTools() {
        return new LinkedHashSet<>(monitoringTools);
    }

    public Map<String, Object> getMetrics() {
        return new HashMap<>(metrics);
    }

    public Set<String> getAlertRules() {
        return new LinkedHashSet<>(alertRules);
    }

    public Set<String> getDashboards() {
        return new LinkedHashSet<>(dashboards);
    }

    public Set<String> getDeploymentTargets() {
        return new LinkedHashSet<>(deploymentTargets);
    }

    public Map<String, String> getDeploymentConfigurations() {
        return new HashMap<>(deploymentConfigurations);
    }

    public Set<String> getEnvironments() {
        return new LinkedHashSet<>(environments);
    }

    // Mutators
    public void addReadinessCheck(String check, String status) {
        Objects.requireNonNull(check, "check cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        this.readinessChecks.add(check);
        this.checkStatuses.put(check, status);
        updateTimestamp();
    }

    public void addHealthCheck(String check, String status) {
        Objects.requireNonNull(check, "check cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        this.healthChecks.add(check);
        this.healthStatuses.put(check, status);
        updateTimestamp();
    }

    public void addMonitoringTool(String tool) {
        Objects.requireNonNull(tool, "tool cannot be null");
        if (this.monitoringTools.add(tool)) {
            updateTimestamp();
        }
    }

    public void addMetric(String name, Object value) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        this.metrics.put(name, value);
        updateTimestamp();
    }

    public void addAlertRule(String rule) {
        Objects.requireNonNull(rule, "rule cannot be null");
        if (this.alertRules.add(rule)) {
            updateTimestamp();
        }
    }

    public void addDashboard(String dashboard) {
        Objects.requireNonNull(dashboard, "dashboard cannot be null");
        if (this.dashboards.add(dashboard)) {
            updateTimestamp();
        }
    }

    public void addDeploymentTarget(String target, String configuration) {
        Objects.requireNonNull(target, "target cannot be null");
        Objects.requireNonNull(configuration, "configuration cannot be null");
        this.deploymentTargets.add(target);
        this.deploymentConfigurations.put(target, configuration);
        updateTimestamp();
    }

    public void addEnvironment(String environment) {
        Objects.requireNonNull(environment, "environment cannot be null");
        if (this.environments.add(environment)) {
            updateTimestamp();
        }
    }

    public void updateCheckStatus(String check, String status) {
        Objects.requireNonNull(check, "check cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        if (this.readinessChecks.contains(check)) {
            this.checkStatuses.put(check, status);
            updateTimestamp();
        }
    }

    public void updateHealthStatus(String check, String status) {
        Objects.requireNonNull(check, "check cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        if (this.healthChecks.contains(check)) {
            this.healthStatuses.put(check, status);
            updateTimestamp();
        }
    }

    // Validation methods
    public boolean isValid() {
        return !readinessChecks.isEmpty() || !healthChecks.isEmpty() || !environments.isEmpty();
    }

    public boolean hasMonitoring() {
        return !monitoringTools.isEmpty() || !metrics.isEmpty();
    }

    public boolean hasDeploymentTargets() {
        return !deploymentTargets.isEmpty();
    }

    public boolean isReady() {
        return !readinessChecks.isEmpty() &&
                readinessChecks.stream().allMatch(check -> "READY".equals(checkStatuses.get(check)) ||
                        "PASSED".equals(checkStatuses.get(check)));
    }

    @Override
    public String toString() {
        return "OperationsContext{" +
                "contextId='" + getContextId() + '\'' +
                ", sessionId='" + getSessionId() + '\'' +
                ", createdAt=" + getCreatedAt() +
                ", lastUpdated=" + getLastUpdated() +
                ", readinessChecks=" + readinessChecks.size() +
                ", healthChecks=" + healthChecks.size() +
                ", monitoringTools=" + monitoringTools.size() +
                ", environments=" + environments.size() +
                '}';
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        private Set<String> readinessChecks = new LinkedHashSet<>();
        private Map<String, String> checkStatuses = new HashMap<>();
        private Set<String> healthChecks = new LinkedHashSet<>();
        private Map<String, String> healthStatuses = new HashMap<>();

        private Set<String> monitoringTools = new LinkedHashSet<>();
        private Map<String, Object> metrics = new HashMap<>();
        private Set<String> alertRules = new LinkedHashSet<>();
        private Set<String> dashboards = new LinkedHashSet<>();

        private Set<String> deploymentTargets = new LinkedHashSet<>();
        private Map<String, String> deploymentConfigurations = new HashMap<>();
        private Set<String> environments = new LinkedHashSet<>();

        public Builder() {
            agentDomain("operations");
            contextType("operations-context");
        }

        public Builder addReadinessCheck(String check, String status) {
            Objects.requireNonNull(check, "check cannot be null");
            Objects.requireNonNull(status, "status cannot be null");
            readinessChecks.add(check);
            checkStatuses.put(check, status);
            return this;
        }

        public Builder readinessChecks(Set<String> checks) {
            if (checks != null) {
                readinessChecks.addAll(checks);
            }
            return this;
        }

        public Builder checkStatuses(Map<String, String> statuses) {
            if (statuses != null) {
                checkStatuses.putAll(statuses);
            }
            return this;
        }

        public Builder addHealthCheck(String check, String status) {
            Objects.requireNonNull(check, "check cannot be null");
            Objects.requireNonNull(status, "status cannot be null");
            healthChecks.add(check);
            healthStatuses.put(check, status);
            return this;
        }

        public Builder healthChecks(Set<String> checks) {
            if (checks != null) {
                healthChecks.addAll(checks);
            }
            return this;
        }

        public Builder healthStatuses(Map<String, String> statuses) {
            if (statuses != null) {
                healthStatuses.putAll(statuses);
            }
            return this;
        }

        public Builder addMonitoringTool(String tool) {
            Objects.requireNonNull(tool, "tool cannot be null");
            monitoringTools.add(tool);
            return this;
        }

        public Builder monitoringTools(Set<String> tools) {
            if (tools != null) {
                monitoringTools.addAll(tools);
            }
            return this;
        }

        public Builder addMetric(String name, Object value) {
            Objects.requireNonNull(name, "name cannot be null");
            Objects.requireNonNull(value, "value cannot be null");
            metrics.put(name, value);
            return this;
        }

        public Builder metrics(Map<String, Object> metrics) {
            if (metrics != null) {
                this.metrics.putAll(metrics);
            }
            return this;
        }

        public Builder addAlertRule(String rule) {
            Objects.requireNonNull(rule, "rule cannot be null");
            alertRules.add(rule);
            return this;
        }

        public Builder alertRules(Set<String> rules) {
            if (rules != null) {
                alertRules.addAll(rules);
            }
            return this;
        }

        public Builder addDashboard(String dashboard) {
            Objects.requireNonNull(dashboard, "dashboard cannot be null");
            dashboards.add(dashboard);
            return this;
        }

        public Builder dashboards(Set<String> dashboards) {
            if (dashboards != null) {
                this.dashboards.addAll(dashboards);
            }
            return this;
        }

        public Builder addDeploymentTarget(String target, String configuration) {
            Objects.requireNonNull(target, "target cannot be null");
            Objects.requireNonNull(configuration, "configuration cannot be null");
            deploymentTargets.add(target);
            deploymentConfigurations.put(target, configuration);
            return this;
        }

        public Builder deploymentTargets(Set<String> targets) {
            if (targets != null) {
                deploymentTargets.addAll(targets);
            }
            return this;
        }

        public Builder deploymentConfigurations(Map<String, String> configs) {
            if (configs != null) {
                deploymentConfigurations.putAll(configs);
            }
            return this;
        }

        public Builder addEnvironment(String environment) {
            Objects.requireNonNull(environment, "environment cannot be null");
            environments.add(environment);
            return this;
        }

        public Builder environments(Set<String> environments) {
            if (environments != null) {
                this.environments.addAll(environments);
            }
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public OperationsContext build() {
            return new OperationsContext(this);
        }
    }
}
