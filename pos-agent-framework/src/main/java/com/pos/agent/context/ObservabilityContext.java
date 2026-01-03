package com.pos.agent.context;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Context model for observability scenarios.
 * Tracks metrics, logs, traces, monitoring, and alerting configurations.
 */
public class ObservabilityContext extends AgentContext {

    // Metrics
    private final Set<String> metricsChecks;
    private final Map<String, Object> metricValues;
    private final Set<String> metricCollectors;

    // Logging
    private final Set<String> logSources;
    private final Map<String, String> logLevels;
    private final Set<String> logAggregators;

    // Tracing
    private final Set<String> tracingSystems;
    private final Map<String, String> traceConfigurations;
    private final Set<String> spanProcessors;

    // Monitoring
    private final Set<String> dashboards;
    private final Set<String> alertRules;
    private final Map<String, String> alertConfigurations;
    private final Set<String> notificationChannels;

    // Health
    private final Set<String> healthEndpoints;
    private final Map<String, String> healthStatuses;

    private ObservabilityContext(Builder builder) {
        super(builder);
        this.metricsChecks = builder.metricsChecks;
        this.metricValues = builder.metricValues;
        this.metricCollectors = builder.metricCollectors;

        this.logSources = builder.logSources;
        this.logLevels = builder.logLevels;
        this.logAggregators = builder.logAggregators;

        this.tracingSystems = builder.tracingSystems;
        this.traceConfigurations = builder.traceConfigurations;
        this.spanProcessors = builder.spanProcessors;

        this.dashboards = builder.dashboards;
        this.alertRules = builder.alertRules;
        this.alertConfigurations = builder.alertConfigurations;
        this.notificationChannels = builder.notificationChannels;

        this.healthEndpoints = builder.healthEndpoints;
        this.healthStatuses = builder.healthStatuses;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public Set<String> getMetricsChecks() {
        return new LinkedHashSet<>(metricsChecks);
    }

    public Map<String, Object> getMetricValues() {
        return new HashMap<>(metricValues);
    }

    public Set<String> getMetricCollectors() {
        return new LinkedHashSet<>(metricCollectors);
    }

    public Set<String> getLogSources() {
        return new LinkedHashSet<>(logSources);
    }

    public Map<String, String> getLogLevels() {
        return new HashMap<>(logLevels);
    }

    public Set<String> getLogAggregators() {
        return new LinkedHashSet<>(logAggregators);
    }

    public Set<String> getTracingSystems() {
        return new LinkedHashSet<>(tracingSystems);
    }

    public Map<String, String> getTraceConfigurations() {
        return new HashMap<>(traceConfigurations);
    }

    public Set<String> getSpanProcessors() {
        return new LinkedHashSet<>(spanProcessors);
    }

    public Set<String> getDashboards() {
        return new LinkedHashSet<>(dashboards);
    }

    public Set<String> getAlertRules() {
        return new LinkedHashSet<>(alertRules);
    }

    public Map<String, String> getAlertConfigurations() {
        return new HashMap<>(alertConfigurations);
    }

    public Set<String> getNotificationChannels() {
        return new LinkedHashSet<>(notificationChannels);
    }

    public Set<String> getHealthEndpoints() {
        return new LinkedHashSet<>(healthEndpoints);
    }

    public Map<String, String> getHealthStatuses() {
        return new HashMap<>(healthStatuses);
    }

    // Mutators
    public void addMetricsCheck(String check) {
        Objects.requireNonNull(check, "check cannot be null");
        if (this.metricsChecks.add(check)) {
            updateTimestamp();
        }
    }

    public void addMetricValue(String name, Object value) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(value, "value cannot be null");
        this.metricValues.put(name, value);
        updateTimestamp();
    }

    public void addMetricCollector(String collector) {
        Objects.requireNonNull(collector, "collector cannot be null");
        if (this.metricCollectors.add(collector)) {
            updateTimestamp();
        }
    }

    public void addLogSource(String source, String logLevel) {
        Objects.requireNonNull(source, "source cannot be null");
        Objects.requireNonNull(logLevel, "logLevel cannot be null");
        this.logSources.add(source);
        this.logLevels.put(source, logLevel);
        updateTimestamp();
    }

    public void addLogAggregator(String aggregator) {
        Objects.requireNonNull(aggregator, "aggregator cannot be null");
        if (this.logAggregators.add(aggregator)) {
            updateTimestamp();
        }
    }

    public void addTracingSystem(String system, String configuration) {
        Objects.requireNonNull(system, "system cannot be null");
        Objects.requireNonNull(configuration, "configuration cannot be null");
        this.tracingSystems.add(system);
        this.traceConfigurations.put(system, configuration);
        updateTimestamp();
    }

    public void addSpanProcessor(String processor) {
        Objects.requireNonNull(processor, "processor cannot be null");
        if (this.spanProcessors.add(processor)) {
            updateTimestamp();
        }
    }

    public void addDashboard(String dashboard) {
        Objects.requireNonNull(dashboard, "dashboard cannot be null");
        if (this.dashboards.add(dashboard)) {
            updateTimestamp();
        }
    }

    public void addAlertRule(String rule, String configuration) {
        Objects.requireNonNull(rule, "rule cannot be null");
        Objects.requireNonNull(configuration, "configuration cannot be null");
        this.alertRules.add(rule);
        this.alertConfigurations.put(rule, configuration);
        updateTimestamp();
    }

    public void addNotificationChannel(String channel) {
        Objects.requireNonNull(channel, "channel cannot be null");
        if (this.notificationChannels.add(channel)) {
            updateTimestamp();
        }
    }

    public void addHealthEndpoint(String endpoint, String status) {
        Objects.requireNonNull(endpoint, "endpoint cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        this.healthEndpoints.add(endpoint);
        this.healthStatuses.put(endpoint, status);
        updateTimestamp();
    }

    public void updateHealthStatus(String endpoint, String status) {
        Objects.requireNonNull(endpoint, "endpoint cannot be null");
        Objects.requireNonNull(status, "status cannot be null");
        if (this.healthEndpoints.contains(endpoint)) {
            this.healthStatuses.put(endpoint, status);
            updateTimestamp();
        }
    }

    public void updateLogLevel(String source, String level) {
        Objects.requireNonNull(source, "source cannot be null");
        Objects.requireNonNull(level, "level cannot be null");
        if (this.logSources.contains(source)) {
            this.logLevels.put(source, level);
            updateTimestamp();
        }
    }

    // Validation methods
    public boolean isValid() {
        return !metricsChecks.isEmpty() || !logSources.isEmpty() || !tracingSystems.isEmpty();
    }

    public boolean hasMetrics() {
        return !metricsChecks.isEmpty() || !metricCollectors.isEmpty();
    }

    public boolean hasLogging() {
        return !logSources.isEmpty() || !logAggregators.isEmpty();
    }

    public boolean hasTracing() {
        return !tracingSystems.isEmpty();
    }

    public boolean hasAlerting() {
        return !alertRules.isEmpty();
    }

    public boolean isHealthy() {
        return !healthEndpoints.isEmpty() &&
                healthEndpoints.stream().allMatch(endpoint -> "HEALTHY".equals(healthStatuses.get(endpoint)) ||
                        "UP".equals(healthStatuses.get(endpoint)));
    }

    @Override
    public String toString() {
        return "ObservabilityContext{" +
                "contextId='" + getContextId() + '\'' +
                ", sessionId='" + getSessionId() + '\'' +
                ", createdAt=" + getCreatedAt() +
                ", lastUpdated=" + getLastUpdated() +
                ", metricsChecks=" + metricsChecks.size() +
                ", logSources=" + logSources.size() +
                ", tracingSystems=" + tracingSystems.size() +
                ", alertRules=" + alertRules.size() +
                ", healthEndpoints=" + healthEndpoints.size() +
                '}';
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        private Set<String> metricsChecks = new LinkedHashSet<>();
        private Map<String, Object> metricValues = new HashMap<>();
        private Set<String> metricCollectors = new LinkedHashSet<>();

        private Set<String> logSources = new LinkedHashSet<>();
        private Map<String, String> logLevels = new HashMap<>();
        private Set<String> logAggregators = new LinkedHashSet<>();

        private Set<String> tracingSystems = new LinkedHashSet<>();
        private Map<String, String> traceConfigurations = new HashMap<>();
        private Set<String> spanProcessors = new LinkedHashSet<>();

        private Set<String> dashboards = new LinkedHashSet<>();
        private Set<String> alertRules = new LinkedHashSet<>();
        private Map<String, String> alertConfigurations = new HashMap<>();
        private Set<String> notificationChannels = new LinkedHashSet<>();

        private Set<String> healthEndpoints = new LinkedHashSet<>();
        private Map<String, String> healthStatuses = new HashMap<>();

        public Builder() {
            agentDomain("observability");
            contextType("observability-context");
        }

        public Builder addMetricsCheck(String check) {
            Objects.requireNonNull(check, "check cannot be null");
            metricsChecks.add(check);
            return this;
        }

        public Builder metricsChecks(Set<String> checks) {
            if (checks != null) {
                metricsChecks.addAll(checks);
            }
            return this;
        }

        public Builder addMetricValue(String name, Object value) {
            Objects.requireNonNull(name, "name cannot be null");
            Objects.requireNonNull(value, "value cannot be null");
            metricValues.put(name, value);
            return this;
        }

        public Builder metricValues(Map<String, Object> values) {
            if (values != null) {
                metricValues.putAll(values);
            }
            return this;
        }

        public Builder addMetricCollector(String collector) {
            Objects.requireNonNull(collector, "collector cannot be null");
            metricCollectors.add(collector);
            return this;
        }

        public Builder metricCollectors(Set<String> collectors) {
            if (collectors != null) {
                metricCollectors.addAll(collectors);
            }
            return this;
        }

        public Builder addLogSource(String source, String logLevel) {
            Objects.requireNonNull(source, "source cannot be null");
            Objects.requireNonNull(logLevel, "logLevel cannot be null");
            logSources.add(source);
            logLevels.put(source, logLevel);
            return this;
        }

        public Builder logSources(Set<String> sources) {
            if (sources != null) {
                logSources.addAll(sources);
            }
            return this;
        }

        public Builder logLevels(Map<String, String> levels) {
            if (levels != null) {
                logLevels.putAll(levels);
            }
            return this;
        }

        public Builder addLogAggregator(String aggregator) {
            Objects.requireNonNull(aggregator, "aggregator cannot be null");
            logAggregators.add(aggregator);
            return this;
        }

        public Builder logAggregators(Set<String> aggregators) {
            if (aggregators != null) {
                logAggregators.addAll(aggregators);
            }
            return this;
        }

        public Builder addTracingSystem(String system, String configuration) {
            Objects.requireNonNull(system, "system cannot be null");
            Objects.requireNonNull(configuration, "configuration cannot be null");
            tracingSystems.add(system);
            traceConfigurations.put(system, configuration);
            return this;
        }

        public Builder tracingSystems(Set<String> systems) {
            if (systems != null) {
                tracingSystems.addAll(systems);
            }
            return this;
        }

        public Builder traceConfigurations(Map<String, String> configs) {
            if (configs != null) {
                traceConfigurations.putAll(configs);
            }
            return this;
        }

        public Builder addSpanProcessor(String processor) {
            Objects.requireNonNull(processor, "processor cannot be null");
            spanProcessors.add(processor);
            return this;
        }

        public Builder spanProcessors(Set<String> processors) {
            if (processors != null) {
                spanProcessors.addAll(processors);
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

        public Builder addAlertRule(String rule, String configuration) {
            Objects.requireNonNull(rule, "rule cannot be null");
            Objects.requireNonNull(configuration, "configuration cannot be null");
            alertRules.add(rule);
            alertConfigurations.put(rule, configuration);
            return this;
        }

        public Builder alertRules(Set<String> rules) {
            if (rules != null) {
                alertRules.addAll(rules);
            }
            return this;
        }

        public Builder alertConfigurations(Map<String, String> configs) {
            if (configs != null) {
                alertConfigurations.putAll(configs);
            }
            return this;
        }

        public Builder addNotificationChannel(String channel) {
            Objects.requireNonNull(channel, "channel cannot be null");
            notificationChannels.add(channel);
            return this;
        }

        public Builder notificationChannels(Set<String> channels) {
            if (channels != null) {
                notificationChannels.addAll(channels);
            }
            return this;
        }

        public Builder addHealthEndpoint(String endpoint, String status) {
            Objects.requireNonNull(endpoint, "endpoint cannot be null");
            Objects.requireNonNull(status, "status cannot be null");
            healthEndpoints.add(endpoint);
            healthStatuses.put(endpoint, status);
            return this;
        }

        public Builder healthEndpoints(Set<String> endpoints) {
            if (endpoints != null) {
                healthEndpoints.addAll(endpoints);
            }
            return this;
        }

        public Builder healthStatuses(Map<String, String> statuses) {
            if (statuses != null) {
                healthStatuses.putAll(statuses);
            }
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public ObservabilityContext build() {
            return new ObservabilityContext(this);
        }
    }
}
