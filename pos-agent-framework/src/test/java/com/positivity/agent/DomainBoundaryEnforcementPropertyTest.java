package com.positivity.agent;

import com.positivity.agent.impl.ArchitecturalGovernanceAgent;
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
 * Property-based test for domain boundary enforcement
 * **Feature: agent-structure, Property 7: Domain boundary enforcement**
 * **Validates: Requirements REQ-005.1, REQ-005.3**
 */
class DomainBoundaryEnforcementPropertyTest {

    private AgentRegistry registry;
    private ArchitecturalGovernanceAgent governanceAgent;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
        governanceAgent = new ArchitecturalGovernanceAgent();
        registry.registerAgent(governanceAgent);
    }

    private void ensureSetup() {
        if (governanceAgent == null) {
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
    void domainBoundaryEnforcement(@ForAll("architecturalDecisionRequests") AgentConsultationRequest request) {
        // Ensure setup is complete
        ensureSetup();

        // Given: An architectural governance agent is available
        assertThat(governanceAgent.isAvailable())
                .describedAs("Architectural governance agent should be available")
                .isTrue();

        // When: Making an architectural decision request
        CompletableFuture<AgentGuidanceResponse> responseFuture = governanceAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: The system should provide successful guidance
        assertThat(response.isSuccessful())
                .describedAs("Architectural governance should provide successful guidance for: %s", request.query())
                .isTrue();

        // And: The guidance should enforce domain-driven design principles
        String guidance = response.guidance();
        assertThat(guidance.toLowerCase())
                .describedAs("Guidance should enforce DDD principles")
                .containsAnyOf("domain", "boundary", "ddd", "bounded context", "anti-corruption");

        // And: The guidance should address circular dependency prevention
        if (request.query().toLowerCase().contains("dependency") ||
                request.query().toLowerCase().contains("circular")) {
            assertThat(guidance.toLowerCase())
                    .describedAs("Guidance should address circular dependency prevention")
                    .containsAnyOf("circular", "dependency", "violation", "prevention");
        }

        // And: The guidance should include domain boundary validation
        if (request.query().toLowerCase().contains("domain") ||
                request.query().toLowerCase().contains("boundary")) {
            assertThat(guidance.toLowerCase())
                    .describedAs("Guidance should include domain boundary validation")
                    .containsAnyOf("boundary", "ownership", "violation", "enforcement");
        }

        // And: The response should have high confidence for governance decisions
        assertThat(response.confidence())
                .describedAs("Governance guidance should have high confidence")
                .isGreaterThanOrEqualTo(0.85);

        // And: The response should include actionable recommendations
        assertThat(response.recommendations())
                .describedAs("Governance guidance should include recommendations")
                .isNotEmpty();
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
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "governance",
                "circular dependency scenario: " + scenario,
                Map.of("scenario", scenario));

        // When: Requesting guidance about circular dependencies
        CompletableFuture<AgentGuidanceResponse> responseFuture = governanceAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: The system should provide successful guidance
        assertThat(response.isSuccessful())
                .describedAs("Should provide guidance for circular dependency scenario: %s", scenario)
                .isTrue();

        // And: The guidance should address circular dependency prevention
        String guidance = response.guidance().toLowerCase();
        assertThat(guidance)
                .describedAs("Guidance should address circular dependency prevention")
                .containsAnyOf("circular", "dependency", "violation", "prevention", "remediation");

        // And: The guidance should provide remediation strategies
        assertThat(guidance)
                .describedAs("Guidance should provide remediation strategies")
                .containsAnyOf("event", "interface", "inversion", "extract", "break");
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
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "governance",
                String.format("domain ownership for %s service in %s domain", service, domain),
                Map.of("domain", domain, "service", service));

        // When: Requesting guidance about domain ownership
        CompletableFuture<AgentGuidanceResponse> responseFuture = governanceAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: The system should provide successful guidance
        assertThat(response.isSuccessful())
                .describedAs("Should provide guidance for domain ownership: %s/%s", domain, service)
                .isTrue();

        // And: The guidance should include domain ownership information
        String guidance = response.guidance().toLowerCase();
        assertThat(guidance)
                .describedAs("Guidance should include domain ownership information")
                .containsAnyOf("domain", "ownership", "boundary", "owns", "responsible");

        // And: The guidance should validate proper service placement
        if (guidance.contains("violation") || guidance.contains("warning")) {
            assertThat(guidance)
                    .describedAs("Guidance should provide remediation for violations")
                    .containsAnyOf("remediation", "strategy", "fix", "correct", "move");
        }
    }

    /**
     * Performance test for domain boundary enforcement
     * Ensures governance guidance is provided efficiently
     */
    @Property(tries = 100)
    void domainBoundaryEnforcementPerformance(
            @ForAll("architecturalDecisionRequests") AgentConsultationRequest request) {
        // Ensure setup is complete
        ensureSetup();

        // Given: An architectural governance agent
        assertThat(governanceAgent.isAvailable()).isTrue();

        // When: Requesting governance guidance
        long startTime = System.nanoTime();
        CompletableFuture<AgentGuidanceResponse> responseFuture = governanceAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();
        long endTime = System.nanoTime();
        Duration responseTime = Duration.ofNanos(endTime - startTime);

        // Then: Response should be provided within reasonable time (500ms)
        assertThat(responseTime)
                .describedAs("Domain boundary enforcement should be efficient")
                .isLessThanOrEqualTo(Duration.ofMillis(500));

        // And: The response should be successful
        assertThat(response.isSuccessful())
                .describedAs("Governance guidance should be successful")
                .isTrue();
    }

    @Provide
    Arbitrary<AgentConsultationRequest> architecturalDecisionRequests() {
        return Arbitraries.of(
                // Domain boundary requests
                AgentConsultationRequest.create("governance", "domain boundary enforcement for pos-catalog service",
                        Map.of()),
                AgentConsultationRequest.create("governance",
                        "validate domain boundaries between customer and order services", Map.of()),
                AgentConsultationRequest.create("governance", "ddd principles for microservice architecture", Map.of()),
                AgentConsultationRequest.create("governance", "bounded context definition for POS system", Map.of()),

                // Circular dependency requests
                AgentConsultationRequest.create("governance", "prevent circular dependencies between domains",
                        Map.of()),
                AgentConsultationRequest.create("governance", "dependency validation for order and customer services",
                        Map.of()),
                AgentConsultationRequest.create("governance", "circular dependency detection in microservices",
                        Map.of()),

                // General architectural requests
                AgentConsultationRequest.create("governance", "architectural governance for POS system", Map.of()),
                AgentConsultationRequest.create("governance", "domain-driven design enforcement", Map.of()),
                AgentConsultationRequest.create("governance", "microservice boundary validation", Map.of()));
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