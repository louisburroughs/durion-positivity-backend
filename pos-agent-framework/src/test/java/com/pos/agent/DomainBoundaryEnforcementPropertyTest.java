package com.pos.agent;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

/**
 * Property-based test for domain boundary enforcement
 * **Feature: agent-structure, Property 7: Domain boundary enforcement**
 * **Validates: Requirements REQ-005.1, REQ-005.3**
 */
class DomainBoundaryEnforcementPropertyTest {

        private AgentManager agentManager;

        @BeforeEach
        void setUp() {
                agentManager = new AgentManager();
        }

        private void ensureSetup() {
                if (agentManager == null) {
                        setUp();
                }
        }

        /**
         * Property 7: Domain boundary enforcement
         * For any architectural decision request, the system should enforce
         * domain-driven design principles
         * and prevent circular dependencies
         */
        @Property(tries = 100)
        void domainBoundaryEnforcement(@ForAll("architecturalDecisionRequests") String query) {
                // Ensure setup is complete
                ensureSetup();

                // Given: An agent manager is available
                assertThat(agentManager).isNotNull();

                // When: Making an architectural decision request
                AgentRequest request = AgentRequest.builder()
                                .type("architecture")
                                .context(AgentContext.builder()
                                                .property("query", query)
                                                .property("focus", "domain-boundaries")
                                                .build())
                                .build();

                AgentResponse response = agentManager.processRequest(request);

                // Then: The system should provide successful response
                assertThat(response.isSuccess())
                                .describedAs("Architecture agent should provide successful guidance for: %s", query)
                                .isTrue();

                // And: The response should contain domain-related concepts
                String responseText = response.getOutput();
                if (query.toLowerCase().contains("domain") || query.toLowerCase().contains("boundary")) {
                        assertThat(responseText.toLowerCase())
                                        .describedAs("Response should address domain boundaries")
                                        .contains("domain");
                }

                // And: The response should address circular dependency prevention if requested
                if (query.toLowerCase().contains("dependency") || query.toLowerCase().contains("circular")) {
                        assertThat(responseText.toLowerCase())
                                        .describedAs("Response should address circular dependency prevention")
                                        .containsAnyOf("dependency", "circular", "violation", "prevention");
                }
        }

        /**
         * Circular dependency prevention test
         * Ensures the system can detect and prevent circular dependencies
         */
        @Property(tries = 100)
        void circularDependencyPrevention(@ForAll("circularDependencyScenarios") String scenario) {
                // Ensure setup is complete
                ensureSetup();

                // Given: A request about circular dependencies
                AgentRequest request = AgentRequest.builder()
                                .type("architecture")
                                .context(AgentContext.builder()
                                                .property("query", "circular dependency scenario: " + scenario)
                                                .property("scenario", scenario)
                                                .build())
                                .build();

                // When: Requesting guidance about circular dependencies
                AgentResponse response = agentManager.processRequest(request);

                // Then: The system should provide successful guidance
                assertThat(response.isSuccess())
                                .describedAs("Should provide guidance for circular dependency scenario: %s", scenario)
                                .isTrue();

                // And: The response should address circular dependency prevention
                String guidance = response.getOutput().toLowerCase();
                assertThat(guidance)
                                .describedAs("Response should address circular dependency prevention")
                                .containsAnyOf("circular", "dependency", "violation", "prevention", "remediation");
        }

        /**
         * Domain ownership validation test
         * Ensures the system enforces proper domain ownership rules
         */
        @Property(tries = 100)
        void domainOwnershipValidation(@ForAll("posDomains") String domain,
                        @ForAll("posServices") String service) {
                // Ensure setup is complete
                ensureSetup();

                // Given: A request about domain ownership
                AgentRequest request = AgentRequest.builder()
                                .type("architecture")
                                .context(AgentContext.builder()
                                                .domain(domain)
                                                .property("query", String.format(
                                                                "domain ownership for %s service in %s domain", service,
                                                                domain))
                                                .property("service", service)
                                                .build())
                                .build();

                // When: Requesting guidance about domain ownership
                AgentResponse response = agentManager.processRequest(request);

                // Then: The system should provide successful guidance
                assertThat(response.isSuccess())
                                .describedAs("Should provide guidance for domain ownership: %s/%s", domain, service)
                                .isTrue();

                // And: The response should include domain ownership information
                String guidance = response.getOutput().toLowerCase();
                assertThat(guidance)
                                .describedAs("Response should include domain ownership information")
                                .containsAnyOf("domain", "ownership", "boundary", "owns", "responsible", "service");
        }

        /**
         * Performance test for domain boundary enforcement
         * Ensures guidance is provided efficiently
         */
        @Property(tries = 100)
        void domainBoundaryEnforcementPerformance(
                        @ForAll("architecturalDecisionRequests") String query) {
                // Ensure setup is complete
                ensureSetup();

                // Given: A governance query
                AgentRequest request = AgentRequest.builder()
                                .type("architecture")
                                .context(AgentContext.builder()
                                                .property("query", query)
                                                .build())
                                .build();

                // When: Requesting governance guidance
                long startTime = System.nanoTime();
                AgentResponse response = agentManager.processRequest(request);
                long endTime = System.nanoTime();
                Duration responseTime = Duration.ofNanos(endTime - startTime);

                // Then: Response should be provided within reasonable time (500ms)
                assertThat(responseTime)
                                .describedAs("Domain boundary enforcement should be efficient")
                                .isLessThanOrEqualTo(Duration.ofMillis(500));

                // And: The response should be successful
                assertThat(response.isSuccess())
                                .describedAs("Governance guidance should be successful")
                                .isTrue();
        }

        @Provide
        Arbitrary<String> architecturalDecisionRequests() {
                return Arbitraries.of(
                                // Domain boundary requests
                                "domain boundary enforcement for pos-catalog service",
                                "validate domain boundaries between customer and order services",
                                "ddd principles for microservice architecture",
                                "bounded context definition for POS system",

                                // Circular dependency requests
                                "prevent circular dependencies between domains",
                                "dependency validation for order and customer services",
                                "circular dependency detection in microservices",

                                // General architectural requests
                                "architectural governance for POS system",
                                "domain-driven design enforcement",
                                "microservice boundary validation");
        }

        @Provide
        Arbitrary<String> circularDependencyScenarios() {
                return Arbitraries.of(
                                "customer service depends on order service and order depends on customer",
                                "catalog service has circular dependency with pricing service",
                                "order service creates circular dependency with invoice service",
                                "shared database between customer and order domains",
                                "direct service-to-service database access",
                                "business logic leaking across domain boundaries");
        }

        @Provide
        Arbitrary<String> posDomains() {
                return Arbitraries.of(
                                "catalog", "customer", "order", "pricing",
                                "integration", "infrastructure", "operations", "support");
        }

        @Provide
        Arbitrary<String> posServices() {
                return Arbitraries.of(
                                "pos-catalog", "pos-customer", "pos-order", "pos-invoice",
                                "pos-inventory", "pos-price", "pos-accounting", "pos-vehicle-inventory",
                                "pos-people", "pos-location", "pos-shop-manager", "pos-inquiry");
        }
}