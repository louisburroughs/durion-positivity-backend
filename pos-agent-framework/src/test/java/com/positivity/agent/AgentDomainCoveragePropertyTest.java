package com.positivity.agent;

import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.registry.DefaultAgentRegistry;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Property-based test for agent domain coverage
 * **Feature: agent-structure, Property 1: Agent domain coverage**
 * **Validates: Requirements REQ-001.1**
 */
class AgentDomainCoveragePropertyTest {

    private AgentRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
    }

    /**
     * Property 1: Agent domain coverage
     * For any request for specialized guidance, the system should provide agents
     * for architecture, implementation, testing, and deployment domains
     */
    @Property(tries = 100)
    void agentDomainCoverage(@ForAll("coreDomains") String domain) {
        // Given: A registry with agents covering the four core domains
        registry = new DefaultAgentRegistry();
        setupCoreDomainAgents();

        // When: Requesting guidance for any core domain
        List<Agent> availableAgents = registry.getAgentsForDomain(domain);

        // Then: The system should provide at least one agent for the domain
        assertThat(availableAgents)
                .describedAs("System should provide agents for domain: %s", domain)
                .isNotEmpty();

        // And: All returned agents should be capable of handling the domain
        AgentConsultationRequest testRequest = AgentConsultationRequest.create(
                domain, "test guidance request", Map.of());

        assertThat(availableAgents.stream().allMatch(agent -> agent.canHandle(testRequest)))
                .describedAs("All agents should be able to handle domain: %s", domain)
                .isTrue();

        // And: At least one agent should be available for consultation
        assertThat(availableAgents.stream().anyMatch(Agent::isAvailable))
                .describedAs("At least one agent should be available for domain: %s", domain)
                .isTrue();
    }

    /**
     * Comprehensive coverage test - ensures all four core domains have coverage
     */
    @Property(tries = 100)
    void allCoreDomainsCovered() {
        // Given: A registry with comprehensive domain coverage
        registry = new DefaultAgentRegistry();
        setupCoreDomainAgents();

        // When: Checking coverage for all core domains
        Set<String> coreDomains = Set.of("architecture", "implementation", "testing", "deployment");

        for (String domain : coreDomains) {
            List<Agent> agents = registry.getAgentsForDomain(domain);

            // Then: Each core domain should have at least one agent
            assertThat(agents)
                    .describedAs("Core domain %s should have at least one agent", domain)
                    .isNotEmpty();

            // And: At least one agent should be available
            assertThat(agents.stream().anyMatch(Agent::isAvailable))
                    .describedAs("Core domain %s should have at least one available agent", domain)
                    .isTrue();
        }
    }

    /**
     * Performance requirement - guidance should be provided efficiently
     */
    @Property(tries = 100)
    void guidanceProvisionPerformance(@ForAll("coreDomains") String domain) {
        // Given: A registry with core domain agents
        registry = new DefaultAgentRegistry();
        setupCoreDomainAgents();

        // When: Requesting guidance for any core domain
        long startTime = System.nanoTime();
        List<Agent> agents = registry.getAgentsForDomain(domain);
        long endTime = System.nanoTime();
        Duration responseTime = Duration.ofNanos(endTime - startTime);

        // Then: Response should be provided within reasonable time (1 second)
        assertThat(responseTime)
                .describedAs("Guidance provision should be efficient")
                .isLessThanOrEqualTo(Duration.ofSeconds(1));

        // And: At least one agent should be provided
        assertThat(agents)
                .describedAs("System should provide agents for domain: %s", domain)
                .isNotEmpty();
    }

    @Provide
    Arbitrary<String> coreDomains() {
        return Arbitraries.of(
                "architecture",
                "implementation",
                "testing",
                "deployment");
    }

    private void setupCoreDomainAgents() {
        // Create agents for the four core domains as specified in the design
        registry.registerAgent(createTestAgent("architecture-agent", "Architecture Agent", "architecture",
                Set.of("ddd", "microservices", "integration-patterns")));

        registry.registerAgent(createTestAgent("implementation-agent", "Implementation Agent", "implementation",
                Set.of("spring-boot", "java", "business-logic", "data-access")));

        registry.registerAgent(createTestAgent("testing-agent", "Testing Agent", "testing",
                Set.of("unit-testing", "integration-testing", "contract-testing")));

        registry.registerAgent(createTestAgent("deployment-agent", "Deployment Agent", "deployment",
                Set.of("docker", "aws-fargate", "ecs", "ci-cd")));
    }

    private Agent createTestAgent(String id, String name, String domain, Set<String> capabilities) {
        return new BaseAgent(id, name, domain, capabilities, Set.of(),
                AgentPerformanceSpec.defaultSpec()) {
            @Override
            protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
                return AgentGuidanceResponse.success(
                        request.requestId(),
                        getId(),
                        "Specialized guidance for " + request.domain() + " domain",
                        0.95,
                        List.of("Domain-specific recommendation for " + request.domain()),
                        Duration.ofMillis(100));
            }
        };
    }
}