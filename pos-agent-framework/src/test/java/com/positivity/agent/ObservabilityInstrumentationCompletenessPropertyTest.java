package com.positivity.agent;

import com.positivity.agent.impl.ObservabilityAgent;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.registry.DefaultAgentRegistry;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Property-based test for observability instrumentation completeness
 * **Feature: agent-structure, Property 10: Observability instrumentation
 * completeness**
 * **Validates: Requirements REQ-008.1, REQ-008.2**
 */
class ObservabilityInstrumentationCompletenessPropertyTest {

        private AgentRegistry registry;
        private ObservabilityAgent observabilityAgent;

        @BeforeEach
        void setUp() {
                registry = new DefaultAgentRegistry();
                observabilityAgent = new ObservabilityAgent();
                registry.registerAgent(observabilityAgent);
        }

        private void ensureSetup() {
                if (observabilityAgent == null) {
                        setUp();
                }
        }

        /**
         * Property 10: Observability instrumentation completeness
         * For any microservice implementation, the system should ensure OpenTelemetry
         * instrumentation and RED metrics implementation
         */
        @Property(tries = 100)
        void observabilityInstrumentationCompleteness(
                        @ForAll("microserviceImplementationRequests") AgentConsultationRequest request) {
                // Ensure setup is complete
                ensureSetup();

                // Given: An observability agent is available
                assertThat(observabilityAgent.isAvailable())
                                .describedAs("Observability agent should be available")
                                .isTrue();

                // When: Making a microservice implementation request
                CompletableFuture<AgentGuidanceResponse> responseFuture = observabilityAgent.provideGuidance(request);
                AgentGuidanceResponse response = responseFuture.join();

                // Then: The system should provide successful guidance
                assertThat(response.isSuccessful())
                                .describedAs("Observability agent should provide successful guidance for: %s",
                                                request.query())
                                .isTrue();

                // And: The guidance should ensure OpenTelemetry instrumentation (REQ-008.1)
                String guidance = response.guidance();
                if (request.query().toLowerCase().contains("opentelemetry") ||
                                request.query().toLowerCase().contains("otel") ||
                                request.query().toLowerCase().contains("instrumentation") ||
                                request.query().toLowerCase().contains("tracing")) {
                        assertThat(guidance.toLowerCase())
                                        .describedAs("Guidance should include OpenTelemetry instrumentation recommendations")
                                        .containsAnyOf("opentelemetry", "otel", "instrumentation", "tracing",
                                                        "span", "tracer", "telemetry", "distributed-tracing");
                }

                // And: The guidance should ensure RED metrics implementation (REQ-008.2)
                if (request.query().toLowerCase().contains("metrics") ||
                                request.query().toLowerCase().contains("red-metrics") ||
                                request.query().toLowerCase().contains("rate") ||
                                request.query().toLowerCase().contains("errors") ||
                                request.query().toLowerCase().contains("duration")) {
                        assertThat(guidance.toLowerCase())
                                        .describedAs("Guidance should include RED metrics implementation")
                                        .containsAnyOf("red-metrics", "rate", "errors", "duration", "metrics",
                                                        "counter", "timer", "histogram", "prometheus");
                }

                // And: The guidance should include monitoring stack integration
                if (request.query().toLowerCase().contains("monitoring") ||
                                request.query().toLowerCase().contains("grafana") ||
                                request.query().toLowerCase().contains("prometheus") ||
                                request.query().toLowerCase().contains("jaeger")) {
                        assertThat(guidance.toLowerCase())
                                        .describedAs("Guidance should include monitoring stack integration")
                                        .containsAnyOf("grafana", "prometheus", "jaeger", "dashboards",
                                                        "monitoring", "visualization", "alerts");
                }

                // And: The guidance should be consistent across similar requests
                assertThat(response.confidence())
                                .describedAs("Observability guidance should have high confidence")
                                .isGreaterThanOrEqualTo(0.90);

                // And: The response should include actionable recommendations
                assertThat(response.recommendations())
                                .describedAs("Observability guidance should include recommendations")
                                .isNotEmpty();
        }

        /**
         * OpenTelemetry integration validation test
         * Ensures the system provides comprehensive OpenTelemetry integration guidance
         */
        @Property(tries = 100)
        void openTelemetryIntegrationGuidance(@ForAll("openTelemetryScenarios") String scenario) {
                // Ensure setup is complete
                ensureSetup();

                // Given: A request about OpenTelemetry integration
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "observability",
                                "OpenTelemetry integration: " + scenario,
                                Map.of("scenario", scenario));

                // When: Requesting guidance about OpenTelemetry integration
                CompletableFuture<AgentGuidanceResponse> responseFuture = observabilityAgent.provideGuidance(request);
                AgentGuidanceResponse response = responseFuture.join();

                // Then: The system should provide successful guidance
                assertThat(response.isSuccessful())
                                .describedAs("Should provide guidance for OpenTelemetry scenario: %s", scenario)
                                .isTrue();

                // And: The guidance should address OpenTelemetry integration
                String guidance = response.guidance().toLowerCase();
                assertThat(guidance)
                                .describedAs("Guidance should address OpenTelemetry integration")
                                .containsAnyOf("opentelemetry", "otel", "instrumentation", "tracing", "telemetry");

                // And: The guidance should include specific integration techniques
                if (scenario.toLowerCase().contains("spring-boot")) {
                        assertThat(guidance)
                                        .describedAs("Guidance should include Spring Boot integration techniques")
                                        .containsAnyOf("spring-boot", "starter", "autoconfiguration", "micrometer");
                }

                if (scenario.toLowerCase().contains("custom")) {
                        assertThat(guidance)
                                        .describedAs("Guidance should include custom instrumentation techniques")
                                        .containsAnyOf("tracer", "span", "custom", "manual", "annotation");
                }

                if (scenario.toLowerCase().contains("distributed")) {
                        assertThat(guidance)
                                        .describedAs("Guidance should include distributed tracing techniques")
                                        .containsAnyOf("distributed", "correlation", "context", "propagation");
                }
        }

        /**
         * RED metrics implementation validation test
         * Ensures the system provides comprehensive RED metrics guidance
         */
        @Property(tries = 100)
        void redMetricsImplementationGuidance(@ForAll("redMetricsScenarios") String metricType) {
                // Ensure setup is complete
                ensureSetup();

                // Given: A request about RED metrics implementation
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "observability",
                                "RED metrics implementation: " + metricType,
                                Map.of("metricType", metricType));

                // When: Requesting guidance about RED metrics
                CompletableFuture<AgentGuidanceResponse> responseFuture = observabilityAgent.provideGuidance(request);
                AgentGuidanceResponse response = responseFuture.join();

                // Then: The system should provide successful guidance
                assertThat(response.isSuccessful())
                                .describedAs("Should provide guidance for RED metrics: %s", metricType)
                                .isTrue();

                // And: The guidance should address RED metrics implementation
                String guidance = response.guidance().toLowerCase();
                assertThat(guidance)
                                .describedAs("Guidance should address RED metrics implementation")
                                .containsAnyOf("red-metrics", "rate", "errors", "duration", "metrics");

                // And: The guidance should include specific metric implementation
                if (metricType.toLowerCase().contains("rate")) {
                        assertThat(guidance)
                                        .describedAs("Guidance should include rate metrics implementation")
                                        .containsAnyOf("rate", "throughput", "requests", "counter", "per-second");
                }

                if (metricType.toLowerCase().contains("error")) {
                        assertThat(guidance)
                                        .describedAs("Guidance should include error metrics implementation")
                                        .containsAnyOf("error", "failure", "exception", "4xx", "5xx");
                }

                if (metricType.toLowerCase().contains("duration")) {
                        assertThat(guidance)
                                        .describedAs("Guidance should include duration metrics implementation")
                                        .containsAnyOf("duration", "latency", "response-time", "timer", "histogram");
                }
        }

        /**
         * Monitoring stack integration validation test
         * Ensures the system provides comprehensive monitoring stack guidance
         */
        @Property(tries = 100)
        void monitoringStackIntegrationGuidance(@ForAll("monitoringStackComponents") String component) {
                // Ensure setup is complete
                ensureSetup();

                // Given: A request about monitoring stack integration
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "observability",
                                "Monitoring stack integration: " + component,
                                Map.of("component", component));

                // When: Requesting guidance about monitoring stack
                CompletableFuture<AgentGuidanceResponse> responseFuture = observabilityAgent.provideGuidance(request);
                AgentGuidanceResponse response = responseFuture.join();

                // Then: The system should provide successful guidance
                assertThat(response.isSuccessful())
                                .describedAs("Should provide guidance for monitoring component: %s", component)
                                .isTrue();

                // And: The guidance should address monitoring stack integration
                String guidance = response.guidance().toLowerCase();
                assertThat(guidance)
                                .describedAs("Guidance should address monitoring stack integration")
                                .containsAnyOf("monitoring", "grafana", "prometheus", "jaeger", "dashboards");

                // And: The guidance should include specific component configuration
                if (component.toLowerCase().contains("grafana")) {
                        assertThat(guidance)
                                        .describedAs("Guidance should include Grafana configuration")
                                        .containsAnyOf("grafana", "dashboard", "visualization", "panel", "query");
                }

                if (component.toLowerCase().contains("prometheus")) {
                        assertThat(guidance)
                                        .describedAs("Guidance should include Prometheus configuration")
                                        .containsAnyOf("prometheus", "scrape", "metrics", "endpoint", "target");
                }

                if (component.toLowerCase().contains("jaeger")) {
                        assertThat(guidance)
                                        .describedAs("Guidance should include Jaeger configuration")
                                        .containsAnyOf("jaeger", "tracing", "spans", "collector", "otlp");
                }
        }

        /**
         * Observability guidance performance test
         * Ensures the system provides guidance within acceptable time limits
         */
        @Property(tries = 100)
        void observabilityGuidancePerformance(
                        @ForAll("microserviceImplementationRequests") AgentConsultationRequest request) {
                // Ensure setup is complete
                ensureSetup();

                // Given: An observability agent is available
                assertThat(observabilityAgent.isAvailable()).isTrue();

                // When: Making a guidance request
                long startTime = System.currentTimeMillis();
                CompletableFuture<AgentGuidanceResponse> responseFuture = observabilityAgent.provideGuidance(request);
                AgentGuidanceResponse response = responseFuture.join();
                long endTime = System.currentTimeMillis();

                // Then: The response should be provided within acceptable time limits
                Duration responseTime = Duration.ofMillis(endTime - startTime);
                assertThat(responseTime)
                                .describedAs("Observability guidance should be provided within 3 seconds")
                                .isLessThan(Duration.ofSeconds(3));

                // And: The response should be successful
                assertThat(response.isSuccessful())
                                .describedAs("Observability guidance should be successful")
                                .isTrue();
        }

        // Data generators for property tests

        @Provide
        Arbitrary<AgentConsultationRequest> microserviceImplementationRequests() {
                return Arbitraries.of(
                                AgentConsultationRequest.create("observability",
                                                "implement OpenTelemetry instrumentation", Map.of()),
                                AgentConsultationRequest.create("observability", "add RED metrics monitoring",
                                                Map.of()),
                                AgentConsultationRequest.create("observability", "configure distributed tracing",
                                                Map.of()),
                                AgentConsultationRequest.create("observability", "set up Prometheus metrics", Map.of()),
                                AgentConsultationRequest.create("observability", "create Grafana dashboards", Map.of()),
                                AgentConsultationRequest.create("observability", "implement error tracking", Map.of()),
                                AgentConsultationRequest.create("observability", "add performance monitoring",
                                                Map.of()),
                                AgentConsultationRequest.create("observability", "configure observability stack",
                                                Map.of()),
                                AgentConsultationRequest.create("observability", "instrument microservice endpoints",
                                                Map.of()),
                                AgentConsultationRequest.create("observability", "monitor business metrics", Map.of()));
        }

        @Provide
        Arbitrary<String> openTelemetryScenarios() {
                return Arbitraries.of(
                                "Spring Boot auto-instrumentation",
                                "custom span creation",
                                "distributed tracing setup",
                                "trace context propagation",
                                "OTLP exporter configuration",
                                "sampling configuration",
                                "trace annotation usage",
                                "manual instrumentation",
                                "service mesh integration",
                                "correlation ID propagation");
        }

        @Provide
        Arbitrary<String> redMetricsScenarios() {
                return Arbitraries.of(
                                "rate metrics implementation",
                                "error rate tracking",
                                "duration histogram setup",
                                "request throughput monitoring",
                                "failure rate calculation",
                                "response time percentiles",
                                "business metrics tracking",
                                "SLI metric definition",
                                "custom counter creation",
                                "timer metric configuration");
        }

        @Provide
        Arbitrary<String> monitoringStackComponents() {
                return Arbitraries.of(
                                "Grafana dashboard creation",
                                "Prometheus scrape configuration",
                                "Jaeger tracing setup",
                                "alerting rules definition",
                                "metric visualization",
                                "trace analysis",
                                "monitoring stack integration",
                                "observability pipeline",
                                "telemetry data export",
                                "monitoring endpoint configuration");
        }
}