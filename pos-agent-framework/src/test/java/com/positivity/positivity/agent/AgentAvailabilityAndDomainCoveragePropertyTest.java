package com.positivity.positivity.agent;

import com.positivity.positivity.agent.registry.AgentRegistry;
import com.positivity.positivity.agent.registry.DefaultAgentRegistry;
import net.jqwik.api.*;
import net.jqwik.api.constraints.NotEmpty;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.BeforeEach;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Property-based test for agent availability and domain coverage
 * **Feature: agent-structure, Property 1: Agent availability and domain coverage**
 * **Validates: Requirements REQ-001.1**
 */
class AgentAvailabilityAndDomainCoveragePropertyTest {
    
    private AgentRegistry registry;
    
    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
    }
    
    /**
     * Property 1: Agent availability and domain coverage
     * For any request for specialized agent guidance, the system should provide agents 
     * for all major domain areas within 1 second with 100% coverage of architecture, 
     * implementation, testing, deployment, and observability domains
     */
    @Property(tries = 100)
    void agentAvailabilityAndDomainCoverage(
            @ForAll("majorDomains") @NotEmpty String domain,
            @ForAll("agentCapabilities") @Size(min = 1, max = 5) Set<String> capabilities) {
        
        // Given: A registry with agents covering major domains
        setupMajorDomainAgents();
        
        // When: Requesting guidance for any major domain
        long startTime = System.nanoTime();
        List<Agent> availableAgents = registry.getAgentsForDomain(domain);
        long endTime = System.nanoTime();
        Duration responseTime = Duration.ofNanos(endTime - startTime);
        
        // Then: Response time should be within 1 second
        Assertions.assertThat(responseTime).isLessThanOrEqualTo(Duration.ofSeconds(1));
        
        // And: At least one agent should be available for the domain
        Assertions.assertThat(availableAgents).isNotEmpty();
        
        // And: All returned agents should be available
        Assertions.assertThat(availableAgents)
                .allMatch(Agent::isAvailable, "All agents should be available");
        
        // And: All returned agents should handle the domain
        AgentConsultationRequest testRequest = AgentConsultationRequest.create(
                domain, "test query", Map.of());
        
        Assertions.assertThat(availableAgents)
                .allMatch(agent -> agent.canHandle(testRequest), 
                        "All agents should be able to handle the domain");
    }
    
    @Property(tries = 100)
    void allMajorDomainsHaveCoverage(@ForAll("majorDomains") String domain) {
        // Given: A registry with comprehensive domain coverage
        setupMajorDomainAgents();
        
        // When: Checking coverage for any major domain
        List<Agent> agents = registry.getAgentsForDomain(domain);
        
        // Then: There should be 100% coverage (at least one agent per domain)
        Assertions.assertThat(agents)
                .as("Domain %s should have at least one agent", domain)
                .isNotEmpty();
        
        // And: At least one agent should be available
        Assertions.assertThat(agents.stream().anyMatch(Agent::isAvailable))
                .as("Domain %s should have at least one available agent", domain)
                .isTrue();
    }
    
    @Property(tries = 100)
    void registryDiscoveryPerformance(@ForAll("consultationRequests") AgentConsultationRequest request) {
        // Given: A registry with multiple agents
        setupMajorDomainAgents();
        
        // When: Finding the best agent for any request
        long startTime = System.nanoTime();
        Optional<Agent> bestAgent = registry.findBestAgent(request);
        long endTime = System.nanoTime();
        Duration discoveryTime = Duration.ofNanos(endTime - startTime);
        
        // Then: Discovery should complete within 1 second
        Assertions.assertThat(discoveryTime).isLessThanOrEqualTo(Duration.ofSeconds(1));
        
        // And: If an agent is found, it should be available and capable
        if (bestAgent.isPresent()) {
            Agent agent = bestAgent.get();
            Assertions.assertThat(agent.isAvailable())
                    .as("Selected agent should be available")
                    .isTrue();
            Assertions.assertThat(agent.canHandle(request))
                    .as("Selected agent should be able to handle the request")
                    .isTrue();
        }
    }
    
    @Provide
    Arbitrary<String> majorDomains() {
        return Arbitraries.of(
                "architecture",
                "implementation", 
                "testing",
                "deployment",
                "observability",
                "security",
                "performance",
                "documentation"
        );
    }
    
    @Provide
    Arbitrary<Set<String>> agentCapabilities() {
        return Arbitraries.of(
                "spring-boot", "microservices", "api-design", "database",
                "security", "monitoring", "testing", "deployment",
                "documentation", "performance"
        ).set().ofMinSize(1).ofMaxSize(5);
    }
    
    @Provide
    Arbitrary<AgentConsultationRequest> consultationRequests() {
        return Combinators.combine(
                majorDomains(),
                Arbitraries.strings().alpha().ofMinLength(5).ofMaxLength(50),
                Arbitraries.maps(
                        Arbitraries.strings().alpha().ofMinLength(3).ofMaxLength(10),
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20)
                ).ofMaxSize(3)
        ).as((domain, query, context) -> 
                AgentConsultationRequest.create(domain, query, context));
    }
    
    private void setupMajorDomainAgents() {
        // Create test agents for all major domains
        registry.registerAgent(createTestAgent("arch-1", "Architecture Agent", "architecture", 
                Set.of("ddd", "microservices", "patterns")));
        
        registry.registerAgent(createTestAgent("impl-1", "Spring Boot Developer", "implementation", 
                Set.of("spring-boot", "java", "business-logic")));
        
        registry.registerAgent(createTestAgent("test-1", "Testing Agent", "testing", 
                Set.of("unit-testing", "integration-testing", "quality")));
        
        registry.registerAgent(createTestAgent("deploy-1", "DevOps Agent", "deployment", 
                Set.of("docker", "aws", "ci-cd")));
        
        registry.registerAgent(createTestAgent("obs-1", "SRE Agent", "observability", 
                Set.of("monitoring", "metrics", "tracing")));
        
        registry.registerAgent(createTestAgent("sec-1", "Security Agent", "security", 
                Set.of("jwt", "encryption", "owasp")));
        
        registry.registerAgent(createTestAgent("perf-1", "Performance Agent", "performance", 
                Set.of("optimization", "caching", "scaling")));
        
        registry.registerAgent(createTestAgent("doc-1", "Documentation Agent", "documentation", 
                Set.of("technical-docs", "api-docs", "standards")));
    }
    
    private Agent createTestAgent(String id, String name, String domain, Set<String> capabilities) {
        return new BaseAgent(id, name, domain, capabilities, Set.of(), 
                AgentPerformanceSpec.defaultSpec()) {
            @Override
            protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
                return AgentGuidanceResponse.success(
                        request.requestId(),
                        getId(),
                        "Test guidance for " + request.domain(),
                        0.95,
                        List.of("Test recommendation"),
                        Duration.ofMillis(100)
                );
            }
        };
    }
}