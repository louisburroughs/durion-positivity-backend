package com.positivity.agent.registry;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.impl.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for enhanced agent registry with all 15 agent types
 * Tests agent discovery, request routing, load balancing, and failover
 * scenarios
 * **Validates: Requirements REQ-001.1, REQ-001.2, REQ-012.1, REQ-013.1,
 * REQ-014.1, REQ-015.1**
 */
@SpringBootTest
class EnhancedAgentRegistryIntegrationTest {

    private AgentRegistry registry;

    // Original agents (11)
    private ArchitectureAgent architectureAgent;
    private ImplementationAgent implementationAgent;
    private TestingAgent testingAgent;
    private DeploymentAgent deploymentAgent;
    private SecurityAgent securityAgent;
    private ObservabilityAgent observabilityAgent;
    private DocumentationAgent documentationAgent;
    private BusinessDomainAgent businessDomainAgent;
    private IntegrationGatewayAgent integrationGatewayAgent;
    private PairProgrammingNavigatorAgent pairNavigatorAgent;
    private ArchitecturalGovernanceAgent architecturalGovernanceAgent;

    // New specialized agents (4)
    private EventDrivenArchitectureAgent eventDrivenAgent;
    private CICDPipelineAgent cicdAgent;
    private ConfigurationManagementAgent configurationAgent;
    private ResilienceEngineeringAgent resilienceAgent;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();

        // Initialize and register all 15 agents
        setupOriginalAgents();
        setupNewSpecializedAgents();
    }

    private void setupOriginalAgents() {
        architectureAgent = new ArchitectureAgent();
        implementationAgent = new ImplementationAgent();
        testingAgent = new TestingAgent();
        deploymentAgent = new DeploymentAgent();
        securityAgent = new SecurityAgent();
        observabilityAgent = new ObservabilityAgent();
        documentationAgent = new DocumentationAgent();
        businessDomainAgent = new BusinessDomainAgent();
        integrationGatewayAgent = new IntegrationGatewayAgent();
        pairNavigatorAgent = new PairProgrammingNavigatorAgent();
        architecturalGovernanceAgent = new ArchitecturalGovernanceAgent();

        registry.registerAgent(architectureAgent);
        registry.registerAgent(implementationAgent);
        registry.registerAgent(testingAgent);
        registry.registerAgent(deploymentAgent);
        registry.registerAgent(securityAgent);
        registry.registerAgent(observabilityAgent);
        registry.registerAgent(documentationAgent);
        registry.registerAgent(businessDomainAgent);
        registry.registerAgent(integrationGatewayAgent);
        registry.registerAgent(pairNavigatorAgent);
        registry.registerAgent(architecturalGovernanceAgent);
    }

    private void setupNewSpecializedAgents() {
        eventDrivenAgent = new EventDrivenArchitectureAgent();
        cicdAgent = new CICDPipelineAgent();
        configurationAgent = new ConfigurationManagementAgent();
        resilienceAgent = new ResilienceEngineeringAgent();

        registry.registerAgent(eventDrivenAgent);
        registry.registerAgent(cicdAgent);
        registry.registerAgent(configurationAgent);
        registry.registerAgent(resilienceAgent);
    }

    /**
     * Test agent discovery with all 15 agent types (REQ-001.1)
     */
    @Test
    void testAgentDiscoveryWithAll15AgentTypes() {
        // When: Getting all agents from registry
        List<Agent> allAgents = registry.getAllAgents();

        // Then: Should have exactly 15 agents
        assertThat(allAgents)
                .describedAs("Registry should contain all 15 agents")
                .hasSize(15);

        // And: Should contain all original agent types
        assertThat(allAgents.stream().map(Agent::getId))
                .describedAs("Should contain all original agents")
                .contains(
                        "architecture-agent",
                        "implementation-agent",
                        "testing-agent",
                        "deployment-agent",
                        "security-agent",
                        "observability-agent",
                        "documentation-agent",
                        "business-domain-agent",
                        "integration-gateway-agent",
                        "pair-navigator-agent",
                        "architectural-governance-agent");

        // And: Should contain all new specialized agent types
        assertThat(allAgents.stream().map(Agent::getId))
                .describedAs("Should contain all new specialized agents")
                .contains(
                        "event-driven-agent",
                        "cicd-pipeline-agent",
                        "configuration-management-agent",
                        "resilience-engineering-agent");
    }

    /**
     * Test request routing to new event-driven agent (REQ-012.1)
     */
    @Test
    void testEventDrivenAgentRouting() {
        // Given: A request for event-driven architecture guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "event-driven",
                "How do I implement Kafka event streaming for microservices?",
                Map.of("context", "microservices-architecture"));

        // When: Finding the best agent for this request
        CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
        AgentGuidanceResponse response = future.join();

        // Then: Should successfully route to event-driven agent
        assertThat(response.status())
                .describedAs("Event-driven request should be successful")
                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

        assertThat(response.agentId())
                .describedAs("Should route to event-driven agent")
                .isEqualTo("event-driven-agent");

        assertThat(response.guidance())
                .describedAs("Should contain Kafka-specific guidance")
                .containsIgnoringCase("kafka");
    }

    /**
     * Test request routing to new CI/CD agent (REQ-013.1)
     */
    @Test
    void testCICDAgentRouting() {
        // Given: A request for CI/CD pipeline guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "cicd",
                "How do I set up Jenkins pipeline with security scanning?",
                Map.of("context", "build-automation"));

        // When: Finding the best agent for this request
        CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
        AgentGuidanceResponse response = future.join();

        // Then: Should successfully route to CI/CD agent
        assertThat(response.status())
                .describedAs("CI/CD request should be successful")
                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

        assertThat(response.agentId())
                .describedAs("Should route to CI/CD agent")
                .isEqualTo("cicd-pipeline-agent");

        assertThat(response.guidance().toLowerCase())
                .describedAs("Should contain Jenkins and security scanning guidance")
                .containsAnyOf("jenkins", "pipeline", "security scanning");
    }

    /**
     * Test request routing to new configuration agent (REQ-014.1)
     */
    @Test
    void testConfigurationAgentRouting() {
        // Given: A request for configuration management guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "configuration",
                "How do I implement feature flags with Spring Cloud Config?",
                Map.of("context", "configuration-management"));

        // When: Finding the best agent for this request
        CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
        AgentGuidanceResponse response = future.join();

        // Then: Should successfully route to configuration agent
        assertThat(response.status())
                .describedAs("Configuration request should be successful")
                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

        assertThat(response.agentId())
                .describedAs("Should route to configuration agent")
                .isEqualTo("configuration-management-agent");

        assertThat(response.guidance().toLowerCase())
                .describedAs("Should contain feature flags and Spring Cloud Config guidance")
                .containsAnyOf("feature flag", "spring cloud config", "configuration");
    }

    /**
     * Test request routing to new resilience agent (REQ-015.1)
     */
    @Test
    void testResilienceAgentRouting() {
        // Given: A request for resilience engineering guidance
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "resilience",
                "How do I implement circuit breakers with Resilience4j?",
                Map.of("context", "resilience-patterns"));

        // When: Finding the best agent for this request
        CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
        AgentGuidanceResponse response = future.join();

        // Then: Should successfully route to resilience agent
        assertThat(response.status())
                .describedAs("Resilience request should be successful")
                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

        assertThat(response.agentId())
                .describedAs("Should route to resilience agent")
                .isEqualTo("resilience-engineering-agent");

        assertThat(response.guidance().toLowerCase())
                .describedAs("Should contain circuit breaker and Resilience4j guidance")
                .containsAnyOf("circuit breaker", "resilience4j", "resilience");
    }

    /**
     * Test load balancing across multiple agent instances (REQ-001.2)
     */
    @Test
    void testLoadBalancingAcrossAgents() {
        // Given: Multiple requests for different domains
        AgentConsultationRequest architectureRequest = AgentConsultationRequest.create(
                "architecture", "Design microservices architecture", Map.of());
        AgentConsultationRequest eventRequest = AgentConsultationRequest.create(
                "event-driven", "Implement event sourcing", Map.of());
        AgentConsultationRequest cicdRequest = AgentConsultationRequest.create(
                "cicd", "Setup build pipeline", Map.of());

        // When: Processing multiple requests concurrently
        CompletableFuture<AgentGuidanceResponse> archFuture = registry.consultBestAgent(architectureRequest);
        CompletableFuture<AgentGuidanceResponse> eventFuture = registry.consultBestAgent(eventRequest);
        CompletableFuture<AgentGuidanceResponse> cicdFuture = registry.consultBestAgent(cicdRequest);

        // Wait for all to complete
        CompletableFuture.allOf(archFuture, eventFuture, cicdFuture).join();

        AgentGuidanceResponse archResponse = archFuture.join();
        AgentGuidanceResponse eventResponse = eventFuture.join();
        AgentGuidanceResponse cicdResponse = cicdFuture.join();

        // Then: Each request should route to appropriate specialized agent
        assertThat(archResponse.agentId())
                .describedAs("Architecture request should route to architecture agent")
                .isEqualTo("architecture-agent");

        assertThat(eventResponse.agentId())
                .describedAs("Event request should route to event-driven agent")
                .isEqualTo("event-driven-agent");

        assertThat(cicdResponse.agentId())
                .describedAs("CI/CD request should route to CI/CD agent")
                .isEqualTo("cicd-pipeline-agent");

        // And: All responses should be successful
        assertThat(archResponse.status()).isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);
        assertThat(eventResponse.status()).isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);
        assertThat(cicdResponse.status()).isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);
    }

    /**
     * Test failover scenarios when agents are unavailable (REQ-001.2)
     */
    @Test
    void testFailoverScenarios() {
        // Given: Registry health status before any failures
        RegistryHealthStatus initialHealth = registry.getHealthStatus();
        assertThat(initialHealth.totalAgents()).isEqualTo(15);
        assertThat(initialHealth.availableAgents()).isEqualTo(15);

        // When: Unregistering a specialized agent to simulate failure
        registry.unregisterAgent("event-driven-agent");

        // Then: Registry should handle the failure gracefully
        RegistryHealthStatus afterFailure = registry.getHealthStatus();
        assertThat(afterFailure.totalAgents()).isEqualTo(14);
        assertThat(afterFailure.availableAgents()).isEqualTo(14);

        // And: Should still be able to route to other agents
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "architecture", "Design system architecture", Map.of());

        CompletableFuture<AgentGuidanceResponse> future = registry.consultBestAgent(request);
        AgentGuidanceResponse response = future.join();

        assertThat(response.status())
                .describedAs("Should still route successfully to available agents")
                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);
    }

    /**
     * Test agent discovery by capabilities for new agent types (REQ-001.1)
     */
    @Test
    void testAgentDiscoveryByCapabilities() {
        // When: Finding agents with event-driven capabilities
        List<Agent> eventAgents = registry.getAgentsWithCapabilities(
                Set.of("event-schemas", "kafka", "message-brokers"));

        // Then: Should find the event-driven agent
        assertThat(eventAgents)
                .describedAs("Should find agents with event-driven capabilities")
                .isNotEmpty()
                .extracting(Agent::getId)
                .contains("event-driven-agent");

        // When: Finding agents with CI/CD capabilities
        List<Agent> cicdAgents = registry.getAgentsWithCapabilities(
                Set.of("build-automation", "deployment-strategies", "security-scanning"));

        // Then: Should find the CI/CD agent
        assertThat(cicdAgents)
                .describedAs("Should find agents with CI/CD capabilities")
                .isNotEmpty()
                .extracting(Agent::getId)
                .contains("cicd-pipeline-agent");

        // When: Finding agents with configuration capabilities
        List<Agent> configAgents = registry.getAgentsWithCapabilities(
                Set.of("spring-cloud-config", "feature-flags", "secrets-management"));

        // Then: Should find the configuration agent
        assertThat(configAgents)
                .describedAs("Should find agents with configuration capabilities")
                .isNotEmpty()
                .extracting(Agent::getId)
                .contains("configuration-management-agent");

        // When: Finding agents with resilience capabilities
        List<Agent> resilienceAgents = registry.getAgentsWithCapabilities(
                Set.of("circuit-breakers", "retry-patterns", "chaos-engineering"));

        // Then: Should find the resilience agent
        assertThat(resilienceAgents)
                .describedAs("Should find agents with resilience capabilities")
                .isNotEmpty()
                .extracting(Agent::getId)
                .contains("resilience-engineering-agent");
    }

    /**
     * Test domain-based agent discovery for new domains (REQ-001.1)
     */
    @Test
    void testDomainBasedAgentDiscovery() {
        // When: Finding agents for event-driven domain
        List<Agent> eventDomainAgents = registry.getAgentsForDomain("event-driven");

        // Then: Should find the event-driven agent
        assertThat(eventDomainAgents)
                .describedAs("Should find agents for event-driven domain")
                .isNotEmpty()
                .extracting(Agent::getId)
                .contains("event-driven-agent");

        // When: Finding agents for CI/CD domain
        List<Agent> cicdDomainAgents = registry.getAgentsForDomain("cicd");

        // Then: Should find the CI/CD agent
        assertThat(cicdDomainAgents)
                .describedAs("Should find agents for CI/CD domain")
                .isNotEmpty()
                .extracting(Agent::getId)
                .contains("cicd-pipeline-agent");

        // When: Finding agents for configuration domain
        List<Agent> configDomainAgents = registry.getAgentsForDomain("configuration");

        // Then: Should find the configuration agent
        assertThat(configDomainAgents)
                .describedAs("Should find agents for configuration domain")
                .isNotEmpty()
                .extracting(Agent::getId)
                .contains("configuration-management-agent");

        // When: Finding agents for resilience domain
        List<Agent> resilienceDomainAgents = registry.getAgentsForDomain("resilience");

        // Then: Should find the resilience agent
        assertThat(resilienceDomainAgents)
                .describedAs("Should find agents for resilience domain")
                .isNotEmpty()
                .extracting(Agent::getId)
                .contains("resilience-engineering-agent");
    }
}