package com.pos.agent.context;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.Objects;

/**
 * Context model for testing scenarios.
 * Captures suites, frameworks, environments, coverage, and defect signals.
 */
public class TestingContext extends AgentContext {

    /**
     * Status values for a test suite.
     */
    public enum TestSuiteStatus {
        PASSED,
        FAILED,
        IN_PROGRESS,
        NOT_STARTED,
        SUCCESS
    }

    private final Set<String> testSuites;
    private final Map<String, TestSuiteStatus> suiteStatuses;
    private final Set<String> frameworks;
    private final Set<String> environments;
    private final Map<String, Double> coverageMetrics;
    private final Set<String> defects;

    private TestingContext(Builder builder) {
        super(builder);
        this.testSuites = builder.testSuites;
        this.suiteStatuses = builder.suiteStatuses;
        this.frameworks = builder.frameworks;
        this.environments = builder.environments;
        this.coverageMetrics = builder.coverageMetrics;
        this.defects = builder.defects;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Set<String> getTestSuites() {
        return new LinkedHashSet<>(testSuites);
    }

    public Map<String, TestSuiteStatus> getSuiteStatuses() {
        return new HashMap<>(suiteStatuses);
    }

    public Set<String> getFrameworks() {
        return new LinkedHashSet<>(frameworks);
    }

    public Set<String> getEnvironments() {
        return new LinkedHashSet<>(environments);
    }

    public Map<String, Double> getCoverageMetrics() {
        return new HashMap<>(coverageMetrics);
    }

    public Set<String> getDefects() {
        return new LinkedHashSet<>(defects);
    }

    // Mutators
    public void addTestSuite(String suite, TestSuiteStatus status) {
        Objects.requireNonNull(suite, "suite must not be null");
        Objects.requireNonNull(status, "status must not be null");
        this.testSuites.add(suite);
        this.suiteStatuses.put(suite, status);
        updateTimestamp();
    }

    public void addFramework(String framework) {
        Objects.requireNonNull(framework, "framework must not be null");
        this.frameworks.add(framework);
        updateTimestamp();
    }

    public void addEnvironment(String environment) {
        Objects.requireNonNull(environment, "environment must not be null");
        this.environments.add(environment);
        updateTimestamp();
    }

    public void addCoverageMetric(String metric, Double value) {
        Objects.requireNonNull(metric, "metric must not be null");
        Objects.requireNonNull(value, "value must not be null");
        this.coverageMetrics.put(metric, value);
        updateTimestamp();
    }

    public void addDefect(String defectId) {
        Objects.requireNonNull(defectId, "defectId must not be null");
        this.defects.add(defectId);
        updateTimestamp();
    }

    public void updateSuiteStatus(String suite, TestSuiteStatus status) {
        Objects.requireNonNull(suite, "suite must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (this.testSuites.contains(suite)) {
            this.suiteStatuses.put(suite, status);
            updateTimestamp();
        }
    }

    // Validation helpers
    public boolean isValid() {
        return !testSuites.isEmpty() || !frameworks.isEmpty();
    }

    public boolean isPassing() {
        return !testSuites.isEmpty() && testSuites.stream().allMatch(suite -> {
            TestSuiteStatus status = suiteStatuses.get(suite);
            return TestSuiteStatus.PASSED == status || TestSuiteStatus.SUCCESS == status;
        });
    }

    @Override
    public String toString() {
        return "TestingContext{" +
                "contextId='" + getContextId() + '\'' +
                ", sessionId='" + getSessionId() + '\'' +
                ", testSuites=" + testSuites.size() +
                ", frameworks=" + frameworks.size() +
                ", environments=" + environments.size() +
                '}';
    }

    public static class Builder extends AgentContext.Builder<Builder> {
        private Set<String> testSuites = new LinkedHashSet<>();
        private Map<String, TestSuiteStatus> suiteStatuses = new HashMap<>();
        private Set<String> frameworks = new LinkedHashSet<>();
        private Set<String> environments = new LinkedHashSet<>();
        private Map<String, Double> coverageMetrics = new HashMap<>();
        private Set<String> defects = new LinkedHashSet<>();

        public Builder() {
            agentDomain("testing");
            contextType("testing-context");
        }

        public Builder addTestSuite(String suite, TestSuiteStatus status) {
            Objects.requireNonNull(suite, "suite must not be null");
            Objects.requireNonNull(status, "status must not be null");
            testSuites.add(suite);
            suiteStatuses.put(suite, status);
            return this;
        }

        public Builder testSuites(Set<String> suites) {
            if (suites != null) {
                this.testSuites.addAll(suites);
            }
            return this;
        }

        public Builder suiteStatuses(Map<String, TestSuiteStatus> statuses) {
            if (statuses != null) {
                this.suiteStatuses.putAll(statuses);
            }
            return this;
        }

        public Builder addFramework(String framework) {
            Objects.requireNonNull(framework, "framework must not be null");
            frameworks.add(framework);
            return this;
        }

        public Builder frameworks(Set<String> frameworks) {
            if (frameworks != null) {
                this.frameworks.addAll(frameworks);
            }
            return this;
        }

        public Builder addEnvironment(String environment) {
            Objects.requireNonNull(environment, "environment must not be null");
            environments.add(environment);
            return this;
        }

        public Builder environments(Set<String> environments) {
            if (environments != null) {
                this.environments.addAll(environments);
            }
            return this;
        }

        public Builder addCoverageMetric(String metric, Double value) {
            Objects.requireNonNull(metric, "metric must not be null");
            Objects.requireNonNull(value, "value must not be null");
            coverageMetrics.put(metric, value);
            return this;
        }

        public Builder coverageMetrics(Map<String, Double> metrics) {
            if (metrics != null) {
                this.coverageMetrics.putAll(metrics);
            }
            return this;
        }

        public Builder addDefect(String defectId) {
            Objects.requireNonNull(defectId, "defectId must not be null");
            defects.add(defectId);
            return this;
        }

        public Builder defects(Set<String> defects) {
            if (defects != null) {
                this.defects.addAll(defects);
            }
            return this;
        }

        @Override
        protected Builder self() {
            return this;
        }

        public TestingContext build() {
            return new TestingContext(this);
        }
    }
}
