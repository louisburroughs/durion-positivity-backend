package com.positivity.agent;

import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.registry.DefaultAgentRegistry;
import net.jqwik.api.*;
import net.jqwik.api.constraints.NotEmpty;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

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
        assertThat(responseTime.compareTo(Duration.ofSeconds(1)) <= 0)
                .describedAs("Response time should be within 1 second")
                .isTrue();
        
        // And: At least one agent should be available for the domain
        assertThat(availableAgents.isEmpty())
                .describedAs("At least one agent should be available for domain: " + domain)
                .isFalse();
        
        // And: All returned agents should be available
        assertThat(availableAgents.stream().allMatch(Agent::isAvailable))
                .describedAs("All agents should be available")
                .isTrue();
        
        // And: All returned agents should handle the domain
        AgentConsultationRequest testRequest = AgentConsultationRequest.create(
                domain, "test query", Map.of());
        
        assertThat(availableAgents.stream().allMatch(agent -> agent.canHandle(testRequest)))
                .describedAs("All agents should be able to handle the domain")
                .isTrue();
    }
    
    @Property(tries = 100)
    void allMajorDomainsHaveCoverage(@ForAll("majorDomains") String domain) {
        // Given: A registry with comprehensive domain coverage
        setupMajorDomainAgents();
        
        // When: Checking coverage for any major domain
        List<Agent> agents = registry.getAgentsForDomain(domain);
        
        // Then: There should be 100% coverage (at least one agent per domain)
        assertThat(agents.isEmpty())
                .describedAs("Domain %s should have at least one agent", domain)
                .isFalse();
        
        // And: At least one agent should be available
        assertThat(agents.stream().anyMatch(Agent::isAvailable))
                .describedAs("Domain %s should have at least one available agent", domain)
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
        assertThat(discoveryTime.compareTo(Duration.ofSeconds(1)) <= 0)
                .describedAs("Discovery should complete within 1 second")
                .isTrue();
        
        // And: If an agent is found, it should be available and capable
        if (bestAgent.isPresent()) {
            Agent agent = bestAgent.get();
            assertThat(agent.isAvailable())
                    .describedAs("Selected agent should be available")
                    .isTrue();
            assertThat(agent.canHandle(request))
                    .describedAs("Selected agent should be able to handle the request")
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