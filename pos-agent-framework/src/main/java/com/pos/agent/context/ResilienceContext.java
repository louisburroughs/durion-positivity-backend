package com.pos.agent.context;

import java.time.Instant;
import java.util.*;
import java.util.Objects;

/**
 * Context model for resilience engineering scenarios.
 * Tracks service reliability, failure handling, and scaling strategies.
 */
public class ResilienceContext extends AgentContext {
    private String serviceName;
    private String platform;
    private String failureType;
    private String scalingType;

    // Specialized collections
    private final Set<String> circuitBreakers;
    private final Map<String, Object> circuitBreakerConfigurations;
    private final Set<String> retryPatterns;
    private final Map<String, Object> retryConfigurations;
    private final Set<String> backoffStrategies;
    private final Set<String> bulkheadPatterns;
    private final Set<String> threadPools;
    private final Set<String> chaosExperiments;
    private final Set<String> healthChecks;
    private final Map<String, Object> sliSloDefinitions;

    private ResilienceContext(Builder builder) {
        super(builder);
        this.serviceName = builder.serviceName;
        this.platform = builder.platform;
        this.failureType = builder.failureType;
        this.scalingType = builder.scalingType;

        this.circuitBreakers = builder.circuitBreakers;
        this.circuitBreakerConfigurations = builder.circuitBreakerConfigurations;
        this.retryPatterns = builder.retryPatterns;
        this.retryConfigurations = builder.retryConfigurations;
        this.backoffStrategies = builder.backoffStrategies;
        this.bulkheadPatterns = builder.bulkheadPatterns;
        this.threadPools = builder.threadPools;
        this.chaosExperiments = builder.chaosExperiments;
        this.healthChecks = builder.healthChecks;
        this.sliSloDefinitions = builder.sliSloDefinitions;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = Objects.requireNonNull(serviceName, "Service name cannot be null");
        updateTimestamp();
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = Objects.requireNonNull(platform, "Platform cannot be null");
        updateTimestamp();
    }

    public String getFailureType() {
        return failureType;
    }

    public void setFailureType(String failureType) {
        this.failureType = Objects.requireNonNull(failureType, "Failure type cannot be null");
        updateTimestamp();
    }

    public String getScalingType() {
        return scalingType;
    }

    public void setScalingType(String scalingType) {
        this.scalingType = Objects.requireNonNull(scalingType, "Scaling type cannot be null");
        updateTimestamp();
    }

    public Instant getCreatedAt() {
        return super.getCreatedAt();
    }

    public Instant getLastUpdated() {
        return super.getLastUpdated();
    }

    public Set<String> getCircuitBreakers() {
        return new LinkedHashSet<>(circuitBreakers);
    }

    public void addCircuitBreaker(String circuitBreaker, Map<String, Object> config) {
        Objects.requireNonNull(circuitBreaker, "Circuit breaker cannot be null");
        Objects.requireNonNull(config, "Circuit breaker config cannot be null");

        if (circuitBreakers.add(circuitBreaker)) {
            circuitBreakerConfigurations.put(circuitBreaker, config);
            updateTimestamp();
        }
    }

    public Set<String> getRetryPatterns() {
        return new LinkedHashSet<>(retryPatterns);
    }

    public void addRetryPattern(String pattern, Map<String, Object> config) {
        Objects.requireNonNull(pattern, "Retry pattern cannot be null");
        Objects.requireNonNull(config, "Retry configuration cannot be null");

        if (retryPatterns.add(pattern)) {
            retryConfigurations.put(pattern, config);
            updateTimestamp();
        }
    }

    public Set<String> getBackoffStrategies() {
        return new LinkedHashSet<>(backoffStrategies);
    }

    public void addBackoffStrategy(String strategy) {
        Objects.requireNonNull(strategy, "Backoff strategy cannot be null");
        if (backoffStrategies.add(strategy)) {
            updateTimestamp();
        }
    }

    public Set<String> getBulkheadPatterns() {
        return new LinkedHashSet<>(bulkheadPatterns);
    }

    public void addBulkheadPattern(String pattern) {
        Objects.requireNonNull(pattern, "Bulkhead pattern cannot be null");
        if (bulkheadPatterns.add(pattern)) {
            updateTimestamp();
        }
    }

    public Set<String> getThreadPools() {
        return new LinkedHashSet<>(threadPools);
    }

    public void addThreadPool(String pool) {
        Objects.requireNonNull(pool, "Thread pool cannot be null");
        if (threadPools.add(pool)) {
            updateTimestamp();
        }
    }

    public Set<String> getChaosExperiments() {
        return new LinkedHashSet<>(chaosExperiments);
    }

    public void addChaosExperiment(String experiment) {
        Objects.requireNonNull(experiment, "Chaos experiment cannot be null");
        if (chaosExperiments.add(experiment)) {
            updateTimestamp();
        }
    }

    public Set<String> getHealthChecks() {
        return new LinkedHashSet<>(healthChecks);
    }

    public void addHealthCheck(String check) {
        Objects.requireNonNull(check, "Health check cannot be null");
        if (healthChecks.add(check)) {
            updateTimestamp();
        }
    }

    public Map<String, Object> getSliSloDefinitions() {
        return new HashMap<>(sliSloDefinitions);
    }

    public void addSliSloDefinition(String key, Object definition) {
        Objects.requireNonNull(key, "SLI/SLO key cannot be null");
        Objects.requireNonNull(definition, "SLI/SLO definition cannot be null");
        this.sliSloDefinitions.put(key, definition);
        updateTimestamp();
    }

    public Map<String, Object> getCircuitBreakerConfigurations() {
        return new HashMap<>(circuitBreakerConfigurations);
    }

    public Map<String, Object> getRetryConfigurations() {
        return new HashMap<>(retryConfigurations);
    }

    protected void updateTimestamp() {
        super.updateTimestamp();
    }

    @Override
    public String toString() {
        return "ResilienceContext{" +
                "serviceName='" + serviceName + '\'' +
                ", platform='" + platform + '\'' +
                ", failureType='" + failureType + '\'' +
                ", scalingType='" + scalingType + '\'' +
                ", createdAt=" + getCreatedAt() +
                ", lastUpdated=" + getLastUpdated() +
                '}';
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        private String serviceName;
        private String platform;
        private String failureType;
        private String scalingType;

        private Set<String> circuitBreakers = new LinkedHashSet<>();
        private Map<String, Object> circuitBreakerConfigurations = new HashMap<>();
        private Set<String> retryPatterns = new LinkedHashSet<>();
        private Map<String, Object> retryConfigurations = new HashMap<>();
        private Set<String> backoffStrategies = new LinkedHashSet<>();
        private Set<String> bulkheadPatterns = new LinkedHashSet<>();
        private Set<String> threadPools = new LinkedHashSet<>();
        private Set<String> chaosExperiments = new LinkedHashSet<>();
        private Set<String> healthChecks = new LinkedHashSet<>();
        private Map<String, Object> sliSloDefinitions = new HashMap<>();

        public Builder() {
            agentDomain("resilience");
            contextType("resilience-context");
        }

        public Builder serviceName(String serviceName) {
            this.serviceName = serviceName;
            return this;
        }

        public Builder platform(String platform) {
            this.platform = platform;
            return this;
        }

        public Builder failureType(String failureType) {
            this.failureType = failureType;
            return this;
        }

        public Builder scalingType(String scalingType) {
            this.scalingType = scalingType;
            return this;
        }

        public Builder addCircuitBreaker(String circuitBreaker, Map<String, Object> config) {
            Objects.requireNonNull(circuitBreaker, "Circuit breaker cannot be null");
            Objects.requireNonNull(config, "Circuit breaker config cannot be null");
            circuitBreakers.add(circuitBreaker);
            circuitBreakerConfigurations.put(circuitBreaker, config);
            return this;
        }

        public Builder circuitBreakers(Set<String> breakers) {
            Objects.requireNonNull(breakers, "Circuit breakers cannot be null");
            circuitBreakers.addAll(breakers);
            return this;
        }

        public Builder circuitBreakerConfigurations(Map<String, Object> configs) {
            Objects.requireNonNull(configs, "Circuit breaker configurations cannot be null");
            circuitBreakerConfigurations.putAll(configs);
            return this;
        }

        public Builder addRetryPattern(String pattern, Map<String, Object> config) {
            Objects.requireNonNull(pattern, "Retry pattern cannot be null");
            Objects.requireNonNull(config, "Retry configuration cannot be null");
            retryPatterns.add(pattern);
            retryConfigurations.put(pattern, config);
            return this;
        }

        public Builder retryPatterns(Set<String> patterns) {
            Objects.requireNonNull(patterns, "Retry patterns cannot be null");
            retryPatterns.addAll(patterns);
            return this;
        }

        public Builder retryConfigurations(Map<String, Object> configs) {
            Objects.requireNonNull(configs, "Retry configurations cannot be null");
            retryConfigurations.putAll(configs);
            return this;
        }

        public Builder addBackoffStrategy(String strategy) {
            Objects.requireNonNull(strategy, "Backoff strategy cannot be null");
            backoffStrategies.add(strategy);
            return this;
        }

        public Builder backoffStrategies(Set<String> strategies) {
            Objects.requireNonNull(strategies, "Backoff strategies cannot be null");
            backoffStrategies.addAll(strategies);
            return this;
        }

        public Builder addBulkheadPattern(String pattern) {
            Objects.requireNonNull(pattern, "Bulkhead pattern cannot be null");
            bulkheadPatterns.add(pattern);
            return this;
        }

        public Builder bulkheadPatterns(Set<String> patterns) {
            Objects.requireNonNull(patterns, "Bulkhead patterns cannot be null");
            bulkheadPatterns.addAll(patterns);
            return this;
        }

        public Builder addThreadPool(String pool) {
            Objects.requireNonNull(pool, "Thread pool cannot be null");
            threadPools.add(pool);
            return this;
        }

        public Builder threadPools(Set<String> pools) {
            Objects.requireNonNull(pools, "Thread pools cannot be null");
            threadPools.addAll(pools);
            return this;
        }

        public Builder addChaosExperiment(String experiment) {
            Objects.requireNonNull(experiment, "Chaos experiment cannot be null");
            chaosExperiments.add(experiment);
            return this;
        }

        public Builder chaosExperiments(Set<String> experiments) {
            Objects.requireNonNull(experiments, "Chaos experiments cannot be null");
            chaosExperiments.addAll(experiments);
            return this;
        }

        public Builder addHealthCheck(String check) {
            Objects.requireNonNull(check, "Health check cannot be null");
            healthChecks.add(check);
            return this;
        }

        public Builder healthChecks(Set<String> checks) {
            Objects.requireNonNull(checks, "Health checks cannot be null");
            healthChecks.addAll(checks);
            return this;
        }

        public Builder addSliSloDefinition(String key, Object definition) {
            Objects.requireNonNull(key, "SLI/SLO key cannot be null");
            Objects.requireNonNull(definition, "SLI/SLO definition cannot be null");
            sliSloDefinitions.put(key, definition);
            return this;
        }

        public Builder sliSloDefinitions(Map<String, Object> definitions) {
            Objects.requireNonNull(definitions, "SLI/SLO definitions cannot be null");
            sliSloDefinitions.putAll(definitions);
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public ResilienceContext build() {
            return new ResilienceContext(this);
        }
    }
}
