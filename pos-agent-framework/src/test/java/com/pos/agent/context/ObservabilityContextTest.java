package com.pos.agent.context;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for ObservabilityContext class.
 * Tests observability aspects including:
 * - Metrics collection and monitoring
 * - Log aggregation and analysis
 * - Distributed tracing systems
 * - Dashboards and alerting
 * - Health endpoints and status monitoring
 */
@DisplayName("ObservabilityContext")
class ObservabilityContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create ObservabilityContext using builder")
        void shouldCreateObservabilityContextUsingBuilder() {
            // When
            ObservabilityContext context = ObservabilityContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'observability'")
        void shouldSetDefaultAgentDomain() {
            // When
            ObservabilityContext context = ObservabilityContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("observability");
        }

        @Test
        @DisplayName("should set default contextType to 'observability-context'")
        void shouldSetDefaultContextType() {
            // When
            ObservabilityContext context = ObservabilityContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("observability-context");
        }

        @Test
        @DisplayName("should add metrics check with value")
        void shouldAddMetricsCheck() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .addMetricsCheck("CPU Usage")
                    .addMetricValue("CPU Usage", "65%")
                    .build();

            // Then
            assertThat(context.getMetricsChecks()).contains("CPU Usage");
            assertThat(context.getMetricValues()).containsEntry("CPU Usage", "65%");
        }

        @Test
        @DisplayName("should add metrics collector")
        void shouldAddMetricsCollector() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .addMetricCollector("Prometheus")
                    .build();

            // Then
            assertThat(context.getMetricCollectors()).contains("Prometheus");
        }

        @Test
        @DisplayName("should add log source with level")
        void shouldAddLogSource() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .addLogSource("Application Logs", "INFO")
                    .build();

            // Then
            assertThat(context.getLogSources()).contains("Application Logs");
            assertThat(context.getLogLevels()).containsEntry("Application Logs", "INFO");
        }

        @Test
        @DisplayName("should add log aggregator")
        void shouldAddLogAggregator() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .addLogAggregator("ELK Stack")
                    .build();

            // Then
            assertThat(context.getLogAggregators()).contains("ELK Stack");
        }


        @Test
        @DisplayName("should add tracing system")
        void shouldAddTracingSystem() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .addTracingSystem("Jaeger", "http://jaeger:6831")
                    .build();

            // Then
            assertThat(context.getTracingSystems()).contains("Jaeger");
            assertThat(context.getTraceConfigurations()).containsEntry("Jaeger", "http://jaeger:6831");
        }

        @Test
        @DisplayName("should add span processor")
        void shouldAddSpanProcessor() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .addSpanProcessor("BatchSpanProcessor")
                    .build();

            // Then
            assertThat(context.getSpanProcessors()).contains("BatchSpanProcessor");
        }

        @Test
        @DisplayName("should add dashboard")
        void shouldAddDashboard() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .addDashboard("Service Overview")
                    .build();

            // Then
            assertThat(context.getDashboards()).contains("Service Overview");
        }

        @Test
        @DisplayName("should add alert rule with configuration")
        void shouldAddAlertRule() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .addAlertRule("High CPU", "cpu > 80%")
                    .build();

            // Then
            assertThat(context.getAlertRules()).contains("High CPU");
            assertThat(context.getAlertConfigurations()).containsEntry("High CPU", "cpu > 80%");
        }

        @Test
        @DisplayName("should add notification channel")
        void shouldAddNotificationChannel() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .addNotificationChannel("Slack")
                    .build();

            // Then
            assertThat(context.getNotificationChannels()).contains("Slack");
        }

        @Test
        @DisplayName("should add health endpoint with status")
        void shouldAddHealthEndpoint() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .addHealthEndpoint("/health", "UP")
                    .build();

            // Then
            assertThat(context.getHealthEndpoints()).contains("/health");
            assertThat(context.getHealthStatuses()).containsEntry("/health", "UP");
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return defensive copy of metrics checks")
        void shouldReturnDefensiveCopyOfMetricsChecks() {
            // Given
            ObservabilityContext context = ObservabilityContext.builder()
                    .addMetricsCheck("Metric1")
                    .build();

            // When
            var checks = context.getMetricsChecks();
            checks.add("Hacked");

            // Then
            assertThat(context.getMetricsChecks()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of log sources")
        void shouldReturnDefensiveCopyOfLogSources() {
            // Given
            ObservabilityContext context = ObservabilityContext.builder()
                    .addLogSource("Log1", "INFO")
                    .build();

            // When
            var sources = context.getLogSources();
            sources.add("Hacked");

            // Then
            assertThat(context.getLogSources()).doesNotContain("Hacked");
        }

        @Test
        @DisplayName("should return defensive copy of tracing systems")
        void shouldReturnDefensiveCopyOfTracingSystems() {
            // Given
            ObservabilityContext context = ObservabilityContext.builder()
                    .addTracingSystem("Trace1", "http://trace1")
                    .build();

            // When
            var systems = context.getTracingSystems();
            systems.add("Hacked");

            // Then
            assertThat(context.getTracingSystems()).doesNotContain("Hacked");
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create comprehensive observability context")
        void shouldCreateComprehensiveObservabilityContext() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .description("Production Observability Stack")
                    .addMetricsCheck("CPU Usage")
                    .addMetricValue("CPU Usage", "65%")
                    .addMetricsCheck("Memory Usage")
                    .addMetricValue("Memory Usage", "72%")
                    .addMetricsCheck("Disk Usage")
                    .addMetricValue("Disk Usage", "45%")
                    .addMetricCollector("Prometheus")
                    .addMetricCollector("Datadog")
                    .addLogSource("Application Logs", "INFO")
                    .addLogSource("System Logs", "WARN")
                    .addLogSource("Access Logs", "INFO")
                    .addLogAggregator("ELK Stack")
                    .addLogAggregator("Splunk")
                    .addTracingSystem("Jaeger", "http://jaeger:6831")
                    .addTracingSystem("Zipkin", "http://zipkin:9411")
                    .addSpanProcessor("BatchSpanProcessor")
                    .addDashboard("Service Overview")
                    .addDashboard("Performance Metrics")
                    .addDashboard("Error Tracking")
                    .addAlertRule("High CPU", "cpu > 80%")
                    .addAlertRule("High Memory", "memory > 85%")
                    .addAlertRule("Service Down", "health == down")
                    .addNotificationChannel("Slack")
                    .addNotificationChannel("PagerDuty")
                    .addNotificationChannel("Email")
                    .addHealthEndpoint("/health", "UP")
                    .addHealthEndpoint("/health/live", "UP")
                    .addHealthEndpoint("/health/ready", "UP")
                    .build();

            // Then
            assertThat(context.getMetricsChecks()).hasSize(3);
            assertThat(context.getMetricCollectors()).hasSize(2);
            assertThat(context.getLogSources()).hasSize(3);
            assertThat(context.getLogAggregators()).hasSize(2);
            assertThat(context.getTracingSystems()).hasSize(2);
            assertThat(context.getSpanProcessors()).hasSize(1);
            assertThat(context.getDashboards()).hasSize(3);
            assertThat(context.getAlertRules()).hasSize(3);
            assertThat(context.getNotificationChannels()).hasSize(3);
            assertThat(context.getHealthEndpoints()).hasSize(3);
        }

        @Test
        @DisplayName("should create observability context with multi-metrics collection")
        void shouldCreateMultiMetricsCollectionContext() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .description("Multi-Metrics Observability")
                    .addMetricCollector("Prometheus")
                    .addMetricCollector("Datadog")
                    .addMetricCollector("CloudWatch")
                    .addMetricsCheck("CPU Usage")
                    .addMetricValue("CPU Usage", "65%")
                    .addMetricsCheck("Request Rate")
                    .addMetricValue("Request Rate", "1000 req/s")
                    .addMetricsCheck("Error Rate")
                    .addMetricValue("Error Rate", "0.5%")
                    .build();

            // Then
            assertThat(context.getMetricCollectors()).hasSize(3);
            assertThat(context.getMetricsChecks()).hasSize(3);
        }

        @Test
        @DisplayName("should create observability context with distributed tracing")
        void shouldCreateDistributedTracingContext() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .description("Distributed Tracing Setup")
                    .addTracingSystem("Jaeger", "http://jaeger:6831")
                    .addSpanProcessor("BatchSpanProcessor")
                    .addSpanProcessor("SimpleSpanProcessor")
                    .addDashboard("Traces Overview")
                    .addAlertRule("Trace Latency High", "trace-duration > 1000ms")
                    .build();

            // Then
            assertThat(context.getTracingSystems()).hasSize(1);
            assertThat(context.getSpanProcessors()).hasSize(2);
            assertThat(context.getAlertRules()).hasSize(1);
        }

        @Test
        @DisplayName("should create observability context with comprehensive alerting and notifications")
        void shouldCreateComprehensiveAlertingContext() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .description("Advanced Alerting Configuration")
                    .addAlertRule("Critical - Service Down", "health == down")
                    .addAlertRule("Warning - High Latency", "latency > 500ms")
                    .addAlertRule("Critical - High Error Rate", "error_rate > 5%")
                    .addAlertRule("Warning - High Memory", "memory > 80%")
                    .addNotificationChannel("Critical Slack")
                    .addNotificationChannel("PagerDuty")
                    .addNotificationChannel("Email")
                    .addNotificationChannel("SMS")
                    .build();

            // Then
            assertThat(context.getAlertRules()).hasSize(4);
            assertThat(context.getNotificationChannels()).hasSize(4);
        }

        @Test
        @DisplayName("should create observability context with multiple health endpoints")
        void shouldCreateMultiHealthEndpointContext() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .description("Kubernetes Probes Configuration")
                    .addHealthEndpoint("/health", "UP")
                    .addHealthEndpoint("/health/live", "UP")
                    .addHealthEndpoint("/health/ready", "UP")
                    .addHealthEndpoint("/health/startup", "UP")
                    .addHealthEndpoint("/health/custom", "UP")
                    .build();

            // Then
            assertThat(context.getHealthEndpoints()).hasSize(5);
            assertThat(context.getHealthStatuses().values()).allMatch(status -> "UP".equals(status));
        }

        @Test
        @DisplayName("should create observability context with comprehensive log aggregation")
        void shouldCreateComprehensiveLogAggregationContext() {
            // When
            ObservabilityContext context = ObservabilityContext.builder()
                    .description("Comprehensive Logging Setup")
                    .addLogSource("Application Logs", "INFO")
                    .addLogSource("System Logs", "WARN")
                    .addLogSource("Access Logs", "INFO")
                    .addLogSource("Audit Logs", "INFO")
                    .addLogSource("Performance Logs", "DEBUG")
                    .addLogAggregator("ELK Stack")
                    .addLogAggregator("Splunk")
                    .addLogAggregator("DataDog")
                    .addDashboard("Log Analysis")
                    .addDashboard("Log Trends")
                    .build();

            // Then
            assertThat(context.getLogSources()).hasSize(5);
            assertThat(context.getLogAggregators()).hasSize(3);
            assertThat(context.getDashboards()).hasSize(2);
        }
    }

    @Nested
    @DisplayName("Null Validation Tests")
    class NullValidationTests {

        @Test
        @DisplayName("should throw NullPointerException when addMetricsCheck is called with null check")
        void shouldThrowNullPointerExceptionForNullMetricsCheck() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addMetricsCheck(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("check cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addMetricValue is called with null name")
        void shouldThrowNullPointerExceptionForNullMetricValueName() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addMetricValue(null, "value")
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("name cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addMetricValue is called with null value")
        void shouldThrowNullPointerExceptionForNullMetricValue() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addMetricValue("name", null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("value cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addMetricCollector is called with null collector")
        void shouldThrowNullPointerExceptionForNullMetricCollector() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addMetricCollector(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("collector cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addLogSource is called with null source")
        void shouldThrowNullPointerExceptionForNullLogSource() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addLogSource(null, "INFO")
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("source cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addLogSource is called with null logLevel")
        void shouldThrowNullPointerExceptionForNullLogLevel() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addLogSource("source", null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("logLevel cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addLogAggregator is called with null aggregator")
        void shouldThrowNullPointerExceptionForNullLogAggregator() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addLogAggregator(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("aggregator cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addTracingSystem is called with null system")
        void shouldThrowNullPointerExceptionForNullTracingSystem() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addTracingSystem(null, "config")
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("system cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addTracingSystem is called with null configuration")
        void shouldThrowNullPointerExceptionForNullTraceConfiguration() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addTracingSystem("system", null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("configuration cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addSpanProcessor is called with null processor")
        void shouldThrowNullPointerExceptionForNullSpanProcessor() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addSpanProcessor(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("processor cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addDashboard is called with null dashboard")
        void shouldThrowNullPointerExceptionForNullDashboard() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addDashboard(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("dashboard cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addAlertRule is called with null rule")
        void shouldThrowNullPointerExceptionForNullAlertRule() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addAlertRule(null, "config")
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("rule cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addAlertRule is called with null configuration")
        void shouldThrowNullPointerExceptionForNullAlertConfiguration() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addAlertRule("rule", null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("configuration cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addNotificationChannel is called with null channel")
        void shouldThrowNullPointerExceptionForNullNotificationChannel() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addNotificationChannel(null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("channel cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addHealthEndpoint is called with null endpoint")
        void shouldThrowNullPointerExceptionForNullHealthEndpoint() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addHealthEndpoint(null, "UP")
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("endpoint cannot be null");
        }

        @Test
        @DisplayName("should throw NullPointerException when addHealthEndpoint is called with null status")
        void shouldThrowNullPointerExceptionForNullHealthStatus() {
            assertThatThrownBy(() -> ObservabilityContext.builder()
                    .addHealthEndpoint("/health", null)
                    .build()).isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("status cannot be null");
        }
    }
}
