package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for OperationsContext class.
 * Tests operational aspects including:
 * - Readiness checks
 * - Health checks
 * - Monitoring tools and metrics
 * - Alert rules and dashboards
 * - Deployment management
 */
@DisplayName("OperationsContext")
class OperationsContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create OperationsContext using builder")
        void shouldCreateOperationsContextUsingBuilder() {
            // When
            OperationsContext context = OperationsContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'operations'")
        void shouldSetDefaultAgentDomain() {
            // When
            OperationsContext context = OperationsContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("operations");
        }

        @Test
        @DisplayName("should set default contextType to 'operations-context'")
        void shouldSetDefaultContextType() {
            // When
            OperationsContext context = OperationsContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("operations-context");
        }

        @Test
        @DisplayName("should add readiness check")
        void shouldAddReadinessCheck() {
            // When
            OperationsContext context = OperationsContext.builder()
                    .addReadinessCheck("Database Connection", "ready")
                    .build();

            // Then
            assertThat(context.getReadinessChecks()).contains("Database Connection");
            assertThat(context.getCheckStatuses()).containsEntry("Database Connection", "ready");
        }

        @Test
        @DisplayName("should add health check")
        void shouldAddHealthCheck() {
            // When
            OperationsContext context = OperationsContext.builder()
                    .addHealthCheck("API Endpoint", "healthy")
                    .build();

            // Then
            assertThat(context.getHealthChecks()).contains("API Endpoint");
            assertThat(context.getHealthStatuses()).containsEntry("API Endpoint", "healthy");
        }

        @Test
        @DisplayName("should add monitoring tool")
        void shouldAddMonitoringTool() {
            // When
            OperationsContext context = OperationsContext.builder()
                    .addMonitoringTool("Prometheus")
                    .build();

            // Then
            assertThat(context.getMonitoringTools()).contains("Prometheus");
        }

        @Test
        @DisplayName("should add metric")
        void shouldAddMetric() {
            // When
            OperationsContext context = OperationsContext.builder()
                    .addMetric("CPU Usage", "75%")
                    .build();

            // Then
            assertThat(context.getMetrics()).containsEntry("CPU Usage", "75%");
        }

        @Test
        @DisplayName("should add alert rule")
        void shouldAddAlertRule() {
            // When
            OperationsContext context = OperationsContext.builder()
                    .addAlertRule("High Memory")
                    .build();

            // Then
            assertThat(context.getAlertRules()).contains("High Memory");
        }

        @Test
        @DisplayName("should add dashboard")
        void shouldAddDashboard() {
            // When
            OperationsContext context = OperationsContext.builder()
                    .addDashboard("Service Monitoring")
                    .build();

            // Then
            assertThat(context.getDashboards()).contains("Service Monitoring");
        }

        @Test
        @DisplayName("should add deployment target")
        void shouldAddDeploymentTarget() {
            // When
            OperationsContext context = OperationsContext.builder()
                    .addDeploymentTarget("prod-cluster", "active")
                    .build();

            // Then
            assertThat(context.getDeploymentTargets()).contains("prod-cluster");
            assertThat(context.getDeploymentConfigurations()).containsEntry("prod-cluster", "active");
        }

        @Test
        @DisplayName("should add environment")
        void shouldAddEnvironment() {
            // When
            OperationsContext context = OperationsContext.builder()
                    .addEnvironment("Production")
                    .build();

            // Then
            assertThat(context.getEnvironments()).contains("Production");
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return defensive copy of readiness checks")
        void shouldReturnDefensiveCopyOfReadinessChecks() {
            // Given
            OperationsContext context = OperationsContext.builder()
                    .addReadinessCheck("Check1", "ready")
                    .build();

            // When
            var checks = context.getReadinessChecks();
            checks.add("Hacked");

            // Then
            assertThat(context.getReadinessChecks()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of health checks")
        void shouldReturnDefensiveCopyOfHealthChecks() {
            // Given
            OperationsContext context = OperationsContext.builder()
                    .addHealthCheck("HealthCheck1", "healthy")
                    .build();

            // When
            var checks = context.getHealthChecks();
            checks.add("Hacked");

            // Then
            assertThat(context.getHealthChecks()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of monitoring tools")
        void shouldReturnDefensiveCopyOfMonitoringTools() {
            // Given
            OperationsContext context = OperationsContext.builder()
                    .addMonitoringTool("Tool1")
                    .build();

            // When
            var tools = context.getMonitoringTools();
            tools.add("Hacked");

            // Then
            assertThat(context.getMonitoringTools()).doesNotContain("Hacked");
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create comprehensive operations monitoring context")
        void shouldCreateComprehensiveOperationsContext() {
            // When
            OperationsContext context = OperationsContext.builder()
                    .description("Production Operations Monitoring")
                    .addReadinessCheck("Database Connection", "ready")
                    .addReadinessCheck("Cache Service", "ready")
                    .addReadinessCheck("Message Queue", "ready")
                    .addHealthCheck("API Endpoint", "healthy")
                    .addHealthCheck("Database Health", "healthy")
                    .addHealthCheck("Service Health", "healthy")
                    .addMonitoringTool("Prometheus")
                    .addMonitoringTool("Grafana")
                    .addMetric("CPU Usage", "65%")
                    .addMetric("Memory Usage", "72%")
                    .addMetric("Disk Usage", "45%")
                    .addMetric("Request Latency", "150ms")
                    .addAlertRule("High CPU")
                    .addAlertRule("High Memory")
                    .addAlertRule("Service Down")
                    .addDashboard("Service Overview")
                    .addDashboard("Performance Metrics")
                    .addDeploymentTarget("prod-us-east-1", "active")
                    .addDeploymentTarget("prod-eu-west-1", "active")
                    .addEnvironment("Production")
                    .build();

            // Then
            assertThat(context.getReadinessChecks()).hasSize(3);
            assertThat(context.getHealthChecks()).hasSize(3);
            assertThat(context.getMonitoringTools()).hasSize(2);
            assertThat(context.getMetrics()).hasSize(4);
            assertThat(context.getAlertRules()).hasSize(3);
            assertThat(context.getDashboards()).hasSize(2);
            assertThat(context.getDeploymentTargets()).hasSize(2);
        }

        @Test
        @DisplayName("should create operations context with multi-region monitoring")
        void shouldCreateMultiRegionOperationsContext() {
            // When
            OperationsContext context = OperationsContext.builder()
                    .description("Multi-Region Production Monitoring")
                    .addReadinessCheck("Database Connection", "ready")
                    .addReadinessCheck("Replication Lag Check", "ready")
                    .addMonitoringTool("Prometheus")
                    .addMonitoringTool("Datadog")
                    .addMetric("Cross-Region Latency", "50ms")
                    .addMetric("Replication Status", "in-sync")
                    .addAlertRule("High Replication Lag")
                    .addAlertRule("Region Failure")
                    .addDashboard("Global Dashboard")
                    .addDeploymentTarget("us-east-1", "active")
                    .addDeploymentTarget("eu-west-1", "active")
                    .addDeploymentTarget("ap-southeast-1", "active")
                    .build();

            // Then
            assertThat(context.getDeploymentTargets()).hasSize(3);
            assertThat(context.getMonitoringTools()).hasSize(2);
            assertThat(context.getAlertRules()).hasSize(2);
        }

        @Test
        @DisplayName("should create operations context with comprehensive alerting")
        void shouldCreateComprehensiveAlertingContext() {
            // When
            OperationsContext context = OperationsContext.builder()
                    .description("Production Alerting Configuration")
                    .addAlertRule("Critical - Service Down")
                    .addAlertRule("Warning - High Latency")
                    .addAlertRule("Critical - High Error Rate")
                    .addAlertRule("Warning - High CPU")
                    .addAlertRule("Critical - Disk Full")
                    .addDashboard("Alert Summary")
                    .addDashboard("On-Call Dashboard")
                    .build();

            // Then
            assertThat(context.getAlertRules()).hasSize(5);
            assertThat(context.getDashboards()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class NullValidationTests {

        @Test
        @DisplayName("Builder.addReadinessCheck should throw NullPointerException when check is null")
        void shouldThrowNPEWhenReadinessCheckIsNull() {
            assertThatThrownBy(() -> OperationsContext.builder()
                    .addReadinessCheck(null, "status")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("check cannot be null");
        }

        @Test
        @DisplayName("Builder.addReadinessCheck should throw NullPointerException when status is null")
        void shouldThrowNPEWhenReadinessStatusIsNull() {
            assertThatThrownBy(() -> OperationsContext.builder()
                    .addReadinessCheck("check", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("status cannot be null");
        }

        @Test
        @DisplayName("Builder.addHealthCheck should throw NullPointerException when check is null")
        void shouldThrowNPEWhenHealthCheckIsNull() {
            assertThatThrownBy(() -> OperationsContext.builder()
                    .addHealthCheck(null, "status")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("check cannot be null");
        }

        @Test
        @DisplayName("Builder.addHealthCheck should throw NullPointerException when status is null")
        void shouldThrowNPEWhenHealthStatusIsNull() {
            assertThatThrownBy(() -> OperationsContext.builder()
                    .addHealthCheck("check", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("status cannot be null");
        }

        @Test
        @DisplayName("Builder.addMonitoringTool should throw NullPointerException when tool is null")
        void shouldThrowNPEWhenMonitoringToolIsNull() {
            assertThatThrownBy(() -> OperationsContext.builder()
                    .addMonitoringTool(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("tool cannot be null");
        }

        @Test
        @DisplayName("Builder.addMetric should throw NullPointerException when name is null")
        void shouldThrowNPEWhenMetricNameIsNull() {
            assertThatThrownBy(() -> OperationsContext.builder()
                    .addMetric(null, "value")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("name cannot be null");
        }

        @Test
        @DisplayName("Builder.addMetric should throw NullPointerException when value is null")
        void shouldThrowNPEWhenMetricValueIsNull() {
            assertThatThrownBy(() -> OperationsContext.builder()
                    .addMetric("name", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("value cannot be null");
        }

        @Test
        @DisplayName("Builder.addAlertRule should throw NullPointerException when rule is null")
        void shouldThrowNPEWhenAlertRuleIsNull() {
            assertThatThrownBy(() -> OperationsContext.builder()
                    .addAlertRule(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("rule cannot be null");
        }

        @Test
        @DisplayName("Builder.addDashboard should throw NullPointerException when dashboard is null")
        void shouldThrowNPEWhenDashboardIsNull() {
            assertThatThrownBy(() -> OperationsContext.builder()
                    .addDashboard(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("dashboard cannot be null");
        }

        @Test
        @DisplayName("Builder.addDeploymentTarget should throw NullPointerException when target is null")
        void shouldThrowNPEWhenDeploymentTargetIsNull() {
            assertThatThrownBy(() -> OperationsContext.builder()
                    .addDeploymentTarget(null, "config")
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("target cannot be null");
        }

        @Test
        @DisplayName("Builder.addDeploymentTarget should throw NullPointerException when configuration is null")
        void shouldThrowNPEWhenDeploymentConfigIsNull() {
            assertThatThrownBy(() -> OperationsContext.builder()
                    .addDeploymentTarget("target", null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("configuration cannot be null");
        }

        @Test
        @DisplayName("Builder.addEnvironment should throw NullPointerException when environment is null")
        void shouldThrowNPEWhenEnvironmentIsNull() {
            assertThatThrownBy(() -> OperationsContext.builder()
                    .addEnvironment(null)
                    .build())
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("environment cannot be null");
        }

        @Test
        @DisplayName("Direct addReadinessCheck should throw NullPointerException when check is null")
        void shouldThrowNPEOnDirectAddReadinessCheckNullCheck() {
            OperationsContext context = OperationsContext.builder().build();
            assertThatThrownBy(() -> context.addReadinessCheck(null, "status"))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("check cannot be null");
        }

        @Test
        @DisplayName("Direct addReadinessCheck should throw NullPointerException when status is null")
        void shouldThrowNPEOnDirectAddReadinessCheckNullStatus() {
            OperationsContext context = OperationsContext.builder().build();
            assertThatThrownBy(() -> context.addReadinessCheck("check", null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("status cannot be null");
        }
    }
}
