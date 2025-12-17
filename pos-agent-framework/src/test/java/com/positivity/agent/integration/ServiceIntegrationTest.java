package com.positivity.agent.integration;

import com.positivity.agent.Agent;
import com.positivity.agent.impl.EventDrivenArchitectureAgent;
import com.positivity.agent.impl.CICDPipelineAgent;
import com.positivity.agent.impl.ConfigurationManagementAgent;
import com.positivity.agent.impl.ResilienceEngineeringAgent;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.context.EventDrivenContext;
import com.positivity.agent.context.CICDContext;
import com.positivity.agent.context.ConfigurationContext;
import com.positivity.agent.context.ResilienceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for new agents with Spring Boot services.
 * Tests integration between new agents and existing microservices.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServiceIntegrationTest {

    private AgentRegistry agentRegistry;
    private EventDrivenArchitectureAgent eventAgent;
    private CICDPipelineAgent cicdAgent;
    private ConfigurationManagementAgent configAgent;
    private ResilienceEngineeringAgent resilienceAgent;

    @BeforeEach
    void setUp() {
        agentRegistry = new AgentRegistry();
        eventAgent = new EventDrivenArchitectureAgent();
        cicdAgent = new CICDPipelineAgent();
        configAgent = new ConfigurationManagementAgent();
        resilienceAgent = new ResilienceEngineeringAgent();
        
        agentRegistry.registerAgent(eventAgent);
        agentRegistry.registerAgent(cicdAgent);
        agentRegistry.registerAgent(configAgent);
        agentRegistry.registerAgent(resilienceAgent);
    }

    @Test
    @DisplayName("Event-driven agent integrates with Kafka messaging services")
    void testEventDrivenAgentKafkaIntegration() {
        // Test Kafka integration patterns
        EventDrivenContext context = new EventDrivenContext();
        context.setEventType("OrderCreated");
        context.setServiceName("pos-order");
        
        String guidance = eventAgent.provideEventSchemaGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("Kafka"));
        assertTrue(guidance.contains("schema"));
        assertTrue(guidance.contains("OrderCreated"));
    }

    @Test
    @DisplayName("CI/CD agent integrates with AWS service deployment")
    void testCICDAgentAWSIntegration() {
        // Test AWS deployment patterns
        CICDContext context = new CICDContext();
        context.setServiceName("pos-inventory");
        context.setDeploymentTarget("AWS");
        context.setEnvironment("production");
        
        String guidance = cicdAgent.provideDeploymentStrategyGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("AWS"));
        assertTrue(guidance.contains("deployment"));
        assertTrue(guidance.contains("production"));
    }

    @Test
    @DisplayName("Configuration agent integrates with Spring Cloud Config")
    void testConfigurationAgentSpringCloudIntegration() {
        // Test Spring Cloud Config integration
        ConfigurationContext context = new ConfigurationContext();
        context.setServiceName("pos-catalog");
        context.setConfigurationType("database");
        context.setEnvironment("development");
        
        String guidance = configAgent.provideSpringCloudConfigGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("Spring Cloud Config"));
        assertTrue(guidance.contains("database"));
        assertTrue(guidance.contains("pos-catalog"));
    }

    @Test
    @DisplayName("Resilience agent integrates with Kubernetes deployment")
    void testResilienceAgentKubernetesIntegration() {
        // Test Kubernetes resilience patterns
        ResilienceContext context = new ResilienceContext();
        context.setServiceName("pos-customer");
        context.setFailureType("network");
        context.setPlatform("kubernetes");
        
        String guidance = resilienceAgent.provideCircuitBreakerGuidance(context);
        
        assertNotNull(guidance);
        assertTrue(guidance.contains("circuit breaker"));
        assertTrue(guidance.contains("kubernetes"));
        assertTrue(guidance.contains("network"));
    }

    @Test
    @DisplayName("Multi-agent collaboration for microservice deployment")
    void testMultiAgentMicroserviceDeployment() {
        // Test collaboration between multiple agents for service deployment
        String serviceName = "pos-vehicle-inventory";
        
        // Event-driven guidance
        EventDrivenContext eventContext = new EventDrivenContext();
        eventContext.setServiceName(serviceName);
        eventContext.setEventType("InventoryUpdated");
        String eventGuidance = eventAgent.provideEventSchemaGuidance(eventContext);
        
        // CI/CD guidance
        CICDContext cicdContext = new CICDContext();
        cicdContext.setServiceName(serviceName);
        cicdContext.setDeploymentTarget("AWS");
        String cicdGuidance = cicdAgent.provideDeploymentStrategyGuidance(cicdContext);
        
        // Configuration guidance
        ConfigurationContext configContext = new ConfigurationContext();
        configContext.setServiceName(serviceName);
        configContext.setConfigurationType("secrets");
        String configGuidance = configAgent.provideSecretsManagementGuidance(configContext);
        
        // Resilience guidance
        ResilienceContext resilienceContext = new ResilienceContext();
        resilienceContext.setServiceName(serviceName);
        resilienceContext.setFailureType("database");
        String resilienceGuidance = resilienceAgent.provideRetryMechanismGuidance(resilienceContext);
        
        // Verify all guidance is provided
        assertNotNull(eventGuidance);
        assertNotNull(cicdGuidance);
        assertNotNull(configGuidance);
        assertNotNull(resilienceGuidance);
        
        // Verify service-specific content
        assertTrue(eventGuidance.contains("InventoryUpdated"));
        assertTrue(cicdGuidance.contains("AWS"));
        assertTrue(configGuidance.contains("secrets"));
        assertTrue(resilienceGuidance.contains("database"));
    }

    @Test
    @DisplayName("Agent registry discovers all service integration capabilities")
    void testAgentRegistryServiceIntegration() {
        // Test that registry can find agents for service integration
        List<Agent> eventAgents = agentRegistry.findAgentsByCapability("event-driven");
        List<Agent> cicdAgents = agentRegistry.findAgentsByCapability("ci-cd");
        List<Agent> configAgents = agentRegistry.findAgentsByCapability("configuration");
        List<Agent> resilienceAgents = agentRegistry.findAgentsByCapability("resilience");
        
        assertFalse(eventAgents.isEmpty());
        assertFalse(cicdAgents.isEmpty());
        assertFalse(configAgents.isEmpty());
        assertFalse(resilienceAgents.isEmpty());
        
        // Verify specific agent types
        assertTrue(eventAgents.stream().anyMatch(a -> a instanceof EventDrivenArchitectureAgent));
        assertTrue(cicdAgents.stream().anyMatch(a -> a instanceof CICDPipelineAgent));
        assertTrue(configAgents.stream().anyMatch(a -> a instanceof ConfigurationManagementAgent));
        assertTrue(resilienceAgents.stream().anyMatch(a -> a instanceof ResilienceEngineeringAgent));
    }

    @Test
    @DisplayName("Service-specific agent routing works correctly")
    void testServiceSpecificAgentRouting() {
        // Test routing to appropriate agents based on service context
        Map<String, String> serviceContext = Map.of(
            "serviceName", "pos-order",
            "domain", "order-processing",
            "technology", "spring-boot"
        );
        
        // Test event-driven routing
        Agent eventAgent = agentRegistry.findBestAgentForContext(serviceContext, "event-schema");
        assertNotNull(eventAgent);
        assertTrue(eventAgent instanceof EventDrivenArchitectureAgent);
        
        // Test CI/CD routing
        Agent cicdAgent = agentRegistry.findBestAgentForContext(serviceContext, "deployment");
        assertNotNull(cicdAgent);
        assertTrue(cicdAgent instanceof CICDPipelineAgent);
        
        // Test configuration routing
        Agent configAgent = agentRegistry.findBestAgentForContext(serviceContext, "configuration");
        assertNotNull(configAgent);
        assertTrue(configAgent instanceof ConfigurationManagementAgent);
        
        // Test resilience routing
        Agent resilienceAgent = agentRegistry.findBestAgentForContext(serviceContext, "resilience");
        assertNotNull(resilienceAgent);
        assertTrue(resilienceAgent instanceof ResilienceEngineeringAgent);
    }
}
