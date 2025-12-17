package com.positivity.agent;

import com.positivity.agent.impl.IntegrationGatewayAgent;
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
 * Property-based test for API Gateway integration consistency
 * **Feature: agent-structure, Property 8: API Gateway integration consistency**
 * **Validates: Requirements REQ-006.1, REQ-006.4**
 */
class ApiGatewayIntegrationConsistencyPropertyTest {

        private AgentRegistry registry;
        private IntegrationGatewayAgent integrationAgent;

        @BeforeEach
        void setUp() {
                registry = new DefaultAgentRegistry();
                integrationAgent = new IntegrationGatewayAgent();
                registry.registerAgent(integrationAgent);
        }

        private void ensureSetup() {
                if (integrationAgent == null) {
                        setUp();
                }
        }

        /**
         * Property 8: API Gateway integration consistency
         * For any API endpoint design request, the system should provide consistent
         * OpenAPI specification and HTTP best practice guidance
         */
        @Property(tries = 100)
        void apiGatewayIntegrationConsistency(@ForAll("apiEndpointDesignRequests") AgentConsultationRequest request) {
                // Ensure setup is complete
                ensureSetup();

                // Given: An integration gateway agent is available
                assertThat(integrationAgent.isAvailable())
                                .describedAs("Integration gateway agent should be available")
                                .isTrue();

                // When: Making an API endpoint design request
                CompletableFuture<AgentGuidanceResponse> responseFuture = integrationAgent.provideGuidance(request);
                AgentGuidanceResponse response = responseFuture.join();

                // Then: The system should provide successful guidance
                assertThat(response.isSuccessful())
                                .describedAs("Integration gateway should provide successful guidance for: %s",
                                                request.query())
                                .isTrue();

                // And: The guidance should include OpenAPI specification guidance
                String guidance = response.guidance();
                if (request.query().toLowerCase().contains("openapi") ||
                                request.query().toLowerCase().contains("swagger") ||
                                (request.query().toLowerCase().contains("api")
                                                && !request.query().toLowerCase().contains("gateway"))
                                ||
                                request.query().toLowerCase().contains("rest")) {
                        assertThat(guidance.toLowerCase())
                                        .describedAs("Guidance should include OpenAPI specification guidance")
                                        .containsAnyOf("openapi", "swagger", "specification", "schema",
                                                        "documentation");
                }

                // And: The guidance should include HTTP best practices
                if (request.query().toLowerCase().contains("rest") ||
                                (request.query().toLowerCase().contains("api")
                                                && !request.query().toLowerCase().contains("gateway"))
                                ||
                                request.query().toLowerCase().contains("http")) {
                        assertThat(guidance.toLowerCase())
                                        .describedAs("Guidance should include HTTP best practices")
                                        .containsAnyOf("http", "status", "method", "get", "post", "put", "delete",
                                                        "patch");
                }

                // And: The guidance should be consistent across similar requests
                assertThat(response.confidence())
                                .describedAs("API Gateway guidance should have high confidence")
                                .isGreaterThanOrEqualTo(0.85);

                // And: The response should include actionable recommendations
                assertThat(response.recommendations())
                                .describedAs("API Gateway guidance should include recommendations")
                                .isNotEmpty();
        }

        /**
         * API versioning and backward compatibility test
         * Ensures the system provides consistent versioning guidance
         */
        @Property(tries = 100)
        void apiVersioningConsistency(@ForAll("apiVersioningScenarios") String scenario) {
                // Ensure setup is complete
                ensureSetup();

                // Given: A request about API versioning
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "integration",
                                "API versioning scenario: " + scenario,
                                Map.of("scenario", scenario));

                // When: Requesting guidance about API versioning
                CompletableFuture<AgentGuidanceResponse> responseFuture = integrationAgent.provideGuidance(request);
                AgentGuidanceResponse response = responseFuture.join();

                // Then: The system should provide successful guidance
                assertThat(response.isSuccessful())
                                .describedAs("Should provide guidance for API versioning scenario: %s", scenario)
                                .isTrue();

                // And: The guidance should address versioning strategies
                String guidance = response.guidance().toLowerCase();
                assertThat(guidance)
                                .describedAs("Guidance should address versioning strategies")
                                .containsAnyOf("version", "compatibility", "backward", "migration", "deprecation");

                // And: The guidance should include contract testing recommendations
                if (guidance.contains("contract") || scenario.toLowerCase().contains("contract")) {
                        assertThat(guidance)
                                        .describedAs("Guidance should include contract testing recommendations")
                                        .containsAnyOf("contract", "testing", "pact", "validation", "schema");
                }
        }

        /**
         * REST API design consistency test
         * Ensures the system provides consistent REST API design guidance
         */
        @Property(tries = 100)
        void restApiDesignConsistency(@ForAll("restApiDesignRequests") AgentConsultationRequest request) {
                // Ensure setup is complete
                ensureSetup();

                // When: Requesting REST API design guidance
                CompletableFuture<AgentGuidanceResponse> responseFuture = integrationAgent.provideGuidance(request);
                AgentGuidanceResponse response = responseFuture.join();

                // Then: The system should provide successful guidance
                assertThat(response.isSuccessful())
                                .describedAs("Should provide guidance for REST API design: %s", request.query())
                                .isTrue();

                // And: The guidance should include REST principles
                String guidance = response.guidance().toLowerCase();
                assertThat(guidance)
                                .describedAs("Guidance should include REST principles")
                                .containsAnyOf("rest", "resource", "noun", "verb", "http", "method");

                // And: The guidance should include status code recommendations
                assertThat(guidance)
                                .describedAs("Guidance should include status code recommendations")
                                .containsAnyOf("200", "201", "400", "401", "404", "500", "status");

                // And: The guidance should be specific to POS domain when applicable
                if (request.query().toLowerCase().contains("pos") ||
                                request.query().toLowerCase().contains("customer") ||
                                request.query().toLowerCase().contains("catalog") ||
                                request.query().toLowerCase().contains("order") ||
                                request.query().toLowerCase().contains("vehicle")) {
                        assertThat(guidance)
                                        .describedAs("Guidance should be specific to POS domain")
                                        .containsAnyOf("customer", "catalog", "order", "inventory", "vehicle");
                }
        }

        /**
         * API Gateway routing consistency test
         * Ensures the system provides consistent routing guidance
         */
        @Property(tries = 100)
        void apiGatewayRoutingConsistency(@ForAll("gatewayRoutingScenarios") String scenario) {
                // Ensure setup is complete
                ensureSetup();

                // Given: A request about API Gateway routing
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "integration",
                                "API Gateway routing: " + scenario,
                                Map.of("scenario", scenario));

                // When: Requesting guidance about routing
                CompletableFuture<AgentGuidanceResponse> responseFuture = integrationAgent.provideGuidance(request);
                AgentGuidanceResponse response = responseFuture.join();

                // Then: The system should provide successful guidance
                assertThat(response.isSuccessful())
                                .describedAs("Should provide guidance for routing scenario: %s", scenario)
                                .isTrue();

                // And: The guidance should address routing strategies
                String guidance = response.guidance().toLowerCase();
                assertThat(guidance)
                                .describedAs("Guidance should address routing strategies")
                                .containsAnyOf("routing", "gateway", "path", "load", "balancing", "service");

                // And: The guidance should include resilience patterns
                assertThat(guidance)
                                .describedAs("Guidance should include resilience patterns")
                                .containsAnyOf("circuit", "breaker", "timeout", "retry", "fallback");
        }

        /**
         * Performance test for API Gateway integration consistency
         * Ensures integration guidance is provided efficiently
         */
        @Property(tries = 100)
        void apiGatewayIntegrationPerformance(
                        @ForAll("apiEndpointDesignRequests") AgentConsultationRequest request) {
                // Ensure setup is complete
                ensureSetup();

                // Given: An integration gateway agent
                assertThat(integrationAgent.isAvailable()).isTrue();

                // When: Requesting integration guidance
                long startTime = System.nanoTime();
                CompletableFuture<AgentGuidanceResponse> responseFuture = integrationAgent.provideGuidance(request);
                AgentGuidanceResponse response = responseFuture.join();
                long endTime = System.nanoTime();
                Duration responseTime = Duration.ofNanos(endTime - startTime);

                // Then: Response should be provided within reasonable time (500ms)
                assertThat(responseTime)
                                .describedAs("API Gateway integration should be efficient")
                                .isLessThanOrEqualTo(Duration.ofMillis(500));

                // And: The response should be successful
                assertThat(response.isSuccessful())
                                .describedAs("Integration guidance should be successful")
                                .isTrue();
        }

        @Provide
        Arbitrary<AgentConsultationRequest> apiEndpointDesignRequests() {
                return Arbitraries.of(
                                // REST API design requests
                                AgentConsultationRequest.create("integration", "REST API design for customer endpoints",
                                                Map.of()),
                                AgentConsultationRequest.create("integration",
                                                "OpenAPI specification for catalog service", Map.of()),
                                AgentConsultationRequest.create("integration", "HTTP best practices for order API",
                                                Map.of()),
                                AgentConsultationRequest.create("integration",
                                                "API endpoint design for vehicle inventory", Map.of()),

                                // API Gateway integration requests
                                AgentConsultationRequest.create("integration", "API Gateway routing for POS services",
                                                Map.of()),
                                AgentConsultationRequest.create("integration", "gateway integration patterns",
                                                Map.of()),
                                AgentConsultationRequest.create("integration",
                                                "API Gateway configuration for microservices", Map.of()),

                                // Versioning and compatibility requests
                                AgentConsultationRequest.create("integration",
                                                "API versioning strategy for customer service", Map.of()),
                                AgentConsultationRequest.create("integration", "backward compatibility for catalog API",
                                                Map.of()),
                                AgentConsultationRequest.create("integration", "contract testing for order endpoints",
                                                Map.of()));
        }

        @Provide
        Arbitrary<String> apiVersioningScenarios() {
                return Arbitraries.of(
                                "migrating customer API from v1 to v2",
                                "maintaining backward compatibility for catalog endpoints",
                                "deprecating old order API version",
                                "contract testing between services",
                                "API schema evolution strategy",
                                "version negotiation for mobile clients");
        }

        @Provide
        Arbitrary<AgentConsultationRequest> restApiDesignRequests() {
                return Arbitraries.of(
                                AgentConsultationRequest.create("integration", "REST API for customer management",
                                                Map.of()),
                                AgentConsultationRequest.create("integration", "catalog API design with product search",
                                                Map.of()),
                                AgentConsultationRequest.create("integration", "order processing API endpoints",
                                                Map.of()),
                                AgentConsultationRequest.create("integration", "vehicle inventory REST API", Map.of()),
                                AgentConsultationRequest.create("integration", "invoice generation API design",
                                                Map.of()),
                                AgentConsultationRequest.create("integration", "work order management endpoints",
                                                Map.of()));
        }

        @Provide
        Arbitrary<String> gatewayRoutingScenarios() {
                return Arbitraries.of(
                                "routing customer requests to pos-customer service",
                                "load balancing for catalog service instances",
                                "path-based routing for different API versions",
                                "header-based routing for mobile vs web clients",
                                "circuit breaker configuration for downstream services",
                                "timeout and retry policies for external integrations");
        }
}