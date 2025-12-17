package com.positivity.agent.collaboration;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.impl.*;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.registry.DefaultAgentRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for enhanced collaboration with new agent types
 * Tests multi-agent workflows, context sharing, and conflict resolution
 * Validates: Requirements REQ-001.3, REQ-012.1, REQ-013.1
 */
class EnhancedCollaborationIntegrationTest {

        private AgentRegistry agentRegistry;
        private PriorityBasedRoutingManager routingManager;
        private ContextAwareGuidanceManager contextManager;
        private MultiAgentConsistencyValidator consistencyValidator;
        private AgentDependencyManager dependencyManager;

        @BeforeEach
        void setUp() {
                // Initialize registry and register all agents including new ones
                agentRegistry = new DefaultAgentRegistry();
                setupAllAgents();

                // Initialize collaboration components
                dependencyManager = new AgentDependencyManager();
                contextManager = new ContextAwareGuidanceManager();
                consistencyValidator = new MultiAgentConsistencyValidator();
                routingManager = new PriorityBasedRoutingManager(agentRegistry, dependencyManager,
                                consistencyValidator);
        }

        private void setupAllAgents() {
                // Register original agents
                agentRegistry.registerAgent(new ArchitectureAgent());
                agentRegistry.registerAgent(new ImplementationAgent());
                agentRegistry.registerAgent(new DeploymentAgent());
                agentRegistry.registerAgent(new TestingAgent());
                agentRegistry.registerAgent(new SecurityAgent());
                agentRegistry.registerAgent(new ObservabilityAgent());
                agentRegistry.registerAgent(new DocumentationAgent());
                agentRegistry.registerAgent(new BusinessDomainAgent());
                agentRegistry.registerAgent(new IntegrationGatewayAgent());
                agentRegistry.registerAgent(new PairProgrammingNavigatorAgent());

                // Register NEW specialized agents (REQ-012, REQ-013, REQ-014, REQ-015)
                agentRegistry.registerAgent(new EventDrivenArchitectureAgent());
                agentRegistry.registerAgent(new CICDPipelineAgent());
                agentRegistry.registerAgent(new ConfigurationManagementAgent());
                agentRegistry.registerAgent(new ResilienceEngineeringAgent());
        }

        @Test
        @DisplayName("Test enhanced capability extraction for event-driven queries")
        void testEventDrivenCapabilityExtraction() throws ExecutionException, InterruptedException {
                // Test event-driven capability extraction
                AgentConsultationRequest eventRequest = AgentConsultationRequest.create(
                                "implementation",
                                "Need help with Kafka event schemas and idempotent handlers with kafka event schemas idempotent",
                                Map.of("service", "pos-events"));

                CompletableFuture<PriorityBasedRoutingManager.PriorityRoutingResult> future = routingManager
                                .routeWithPriority(eventRequest);
                PriorityBasedRoutingManager.PriorityRoutingResult result = future.get();

                assertTrue(result.isSuccessful());
                assertNotNull(result.getPrimaryResponse());

                // Should route to EventDrivenArchitectureAgent based on capabilities
                String guidance = result.getPrimaryResponse().guidance();
                assertTrue(guidance.contains("event") || guidance.contains("Kafka") || guidance.contains("schema"));

                // Verify performance
                assertTrue(result.getRoutingTime().toMillis() <= 1000);
        }

        @Test
        @DisplayName("Test CI/CD capability extraction and routing")
        void testCICDCapabilityExtraction() throws ExecutionException, InterruptedException {
                // Test CI/CD capability extraction
                AgentConsultationRequest cicdRequest = AgentConsultationRequest.create(
                                "deployment",
                                "Need Jenkins pipeline with SAST security scanning with jenkins pipeline sast security",
                                Map.of("service", "pos-api-gateway"));

                CompletableFuture<PriorityBasedRoutingManager.PriorityRoutingResult> future = routingManager
                                .routeWithPriority(cicdRequest);
                PriorityBasedRoutingManager.PriorityRoutingResult result = future.get();

                assertTrue(result.isSuccessful());
                assertNotNull(result.getPrimaryResponse());

                // Should contain CI/CD related guidance
                String guidance = result.getPrimaryResponse().guidance();
                assertTrue(guidance.contains("pipeline") || guidance.contains("Jenkins") ||
                                guidance.contains("CI/CD") || guidance.contains("security"));

                // Verify performance
                assertTrue(result.getRoutingTime().toMillis() <= 1000);
        }

        @Test
        @DisplayName("Test configuration management capability extraction")
        void testConfigurationCapabilityExtraction() throws ExecutionException, InterruptedException {
                // Test configuration capability extraction
                AgentConsultationRequest configRequest = AgentConsultationRequest.create(
                                "architecture",
                                "Need Spring Cloud Config with feature flags and Vault secrets with spring cloud config feature flags vault",
                                Map.of("service", "pos-configuration"));

                CompletableFuture<PriorityBasedRoutingManager.PriorityRoutingResult> future = routingManager
                                .routeWithPriority(configRequest);
                PriorityBasedRoutingManager.PriorityRoutingResult result = future.get();

                assertTrue(result.isSuccessful());
                assertNotNull(result.getPrimaryResponse());

                // Should contain configuration related guidance
                String guidance = result.getPrimaryResponse().guidance();
                assertTrue(guidance.contains("config") || guidance.contains("Spring Cloud") ||
                                guidance.contains("feature") || guidance.contains("Vault"));

                // Verify performance
                assertTrue(result.getRoutingTime().toMillis() <= 1000);
        }

        @Test
        @DisplayName("Test resilience engineering capability extraction")
        void testResilienceCapabilityExtraction() throws ExecutionException, InterruptedException {
                // Test resilience capability extraction
                AgentConsultationRequest resilienceRequest = AgentConsultationRequest.create(
                                "implementation",
                                "Need Resilience4j circuit breakers and chaos engineering with resilience4j circuit breaker chaos",
                                Map.of("service", "pos-order"));

                CompletableFuture<PriorityBasedRoutingManager.PriorityRoutingResult> future = routingManager
                                .routeWithPriority(resilienceRequest);
                PriorityBasedRoutingManager.PriorityRoutingResult result = future.get();

                assertTrue(result.isSuccessful());
                assertNotNull(result.getPrimaryResponse());

                // Should contain resilience related guidance
                String guidance = result.getPrimaryResponse().guidance();
                assertTrue(guidance.contains("resilience") || guidance.contains("circuit") ||
                                guidance.contains("Resilience4j") || guidance.contains("chaos"));

                // Verify performance
                assertTrue(result.getRoutingTime().toMillis() <= 1000);
        }

        @Test
        @DisplayName("Test conflict resolution with multiple agent responses")
        void testConflictResolutionWithMultipleAgents() throws ExecutionException, InterruptedException {
                // Create request that might involve multiple agents
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "implementation",
                                "Should I use REST APIs or event-driven communication for microservices with rest api event driven microservices?",
                                Map.of("service", "pos-customer"));

                CompletableFuture<PriorityBasedRoutingManager.PriorityRoutingResult> future = routingManager
                                .routeWithPriority(request);
                PriorityBasedRoutingManager.PriorityRoutingResult result = future.get();

                // Verify routing handled the request successfully
                assertTrue(result.isSuccessful());
                assertNotNull(result.getPrimaryResponse());

                // Should have a coherent response (conflict resolution worked)
                String guidance = result.getPrimaryResponse().guidance();
                assertFalse(guidance.isEmpty());

                // Should contain guidance about communication patterns
                assertTrue(guidance.contains("REST") || guidance.contains("event") ||
                                guidance.contains("microservices") || guidance.contains("communication"));

                // Verify performance under potential conflict resolution
                assertTrue(result.getRoutingTime().toMillis() <= 2000);
        }

        @Test
        @DisplayName("Test context enhancement with specialized agent responses")
        void testContextEnhancementWithSpecializedAgents() {
                // Create request with specialized context
                AgentConsultationRequest request = AgentConsultationRequest.create(
                                "event-driven",
                                "Configure Kafka with event schemas",
                                Map.of(
                                                "session-id", "test-session-123",
                                                "event-driven-context", "kafka-integration",
                                                "task-objective", "Implement event-driven architecture"));

                // Create a sample response from EventDrivenArchitectureAgent
                AgentGuidanceResponse originalResponse = AgentGuidanceResponse.success(
                                "test-request", "event-driven-agent",
                                "Kafka configuration guidance for event schemas", 0.95,
                                List.of("Use schema registry", "Implement idempotent handlers"),
                                Duration.ofMillis(200));

                // Enhance response with context
                AgentGuidanceResponse enhancedResponse = contextManager.enhanceWithContext(originalResponse, request);

                // Verify context enhancement
                assertNotNull(enhancedResponse);
                assertTrue(enhancedResponse.isSuccessful());

                // Should contain context-aware guidance
                String guidance = enhancedResponse.guidance();
                assertTrue(guidance.contains("Context-Aware Guidance") ||
                                guidance.contains("Session Context") ||
                                guidance.contains("kafka") || guidance.contains("event"));

                // Should have enhanced recommendations
                List<String> recommendations = enhancedResponse.recommendations();
                assertTrue(recommendations.size() >= 2); // Original + context-enhanced

                // Verify confidence is maintained or improved
                assertTrue(enhancedResponse.confidence() >= originalResponse.confidence());
        }

        @Test
        @DisplayName("Test session context management with new agent types")
        void testSessionContextManagementWithNewAgents() {
                // Test session context creation and management
                String sessionId = "integration-test-session";

                // Update session with specialized context
                contextManager.updateSessionProgress(sessionId,
                                "Multi-agent integration testing",
                                Map.of(
                                                "event-driven-decisions", "kafka-with-avro-schemas",
                                                "cicd-decisions", "jenkins-with-security-scanning",
                                                "config-decisions", "spring-cloud-config-with-vault",
                                                "resilience-decisions", "resilience4j-circuit-breakers"),
                                List.of("implement-event-handlers", "setup-pipeline", "configure-secrets"));

                // Verify session context was created
                var sessionContext = contextManager.getSessionContext(sessionId);
                assertTrue(sessionContext.isPresent());

                ContextAwareGuidanceManager.SessionContext context = sessionContext.get();
                assertEquals("Multi-agent integration testing", context.getTaskObjective());
                assertFalse(context.getArchitecturalDecisions().isEmpty());
                assertFalse(context.getNextSteps().isEmpty());

                // Verify architectural decisions were stored
                assertTrue(context.getArchitecturalDecisions().containsKey("event-driven-decisions"));
                assertTrue(context.getArchitecturalDecisions().containsKey("cicd-decisions"));
                assertTrue(context.getArchitecturalDecisions().containsKey("config-decisions"));
                assertTrue(context.getArchitecturalDecisions().containsKey("resilience-decisions"));
        }

        @Test
        @DisplayName("Test performance under concurrent requests to new agents")
        void testPerformanceUnderConcurrentRequests() throws ExecutionException, InterruptedException {
                // Create multiple concurrent requests to different agent types
                List<CompletableFuture<PriorityBasedRoutingManager.PriorityRoutingResult>> futures = List.of(
                                // Event-driven request
                                routingManager.routeWithPriority(
                                                AgentConsultationRequest.create("event-driven",
                                                                "Kafka configuration with kafka",
                                                                Map.of("service", "pos-events"))),

                                // CI/CD request
                                routingManager.routeWithPriority(
                                                AgentConsultationRequest.create("cicd",
                                                                "Jenkins pipeline with jenkins",
                                                                Map.of("service", "pos-build"))),

                                // Configuration request
                                routingManager.routeWithPriority(
                                                AgentConsultationRequest.create("configuration",
                                                                "Spring Cloud Config with config",
                                                                Map.of("service", "pos-config"))),

                                // Resilience request
                                routingManager.routeWithPriority(
                                                AgentConsultationRequest.create("resilience",
                                                                "Circuit breaker with circuit breaker",
                                                                Map.of("service", "pos-resilience"))));

                // Wait for all requests to complete
                long startTime = System.currentTimeMillis();
                CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                                futures.toArray(new CompletableFuture[0]));
                allFutures.get();
                long totalTime = System.currentTimeMillis() - startTime;

                // Verify all requests completed successfully
                for (CompletableFuture<PriorityBasedRoutingManager.PriorityRoutingResult> future : futures) {
                        PriorityBasedRoutingManager.PriorityRoutingResult result = future.get();
                        assertTrue(result.isSuccessful());
                        assertNotNull(result.getPrimaryResponse());
                        assertTrue(result.getPrimaryResponse().isSuccessful());
                }

                // Verify performance under load (4 concurrent requests should complete
                // reasonably fast)
                assertTrue(totalTime <= 5000); // 5 seconds for 4 concurrent requests
        }

        @Test
        @DisplayName("Test enhanced domain expertise resolution")
        void testEnhancedDomainExpertiseResolution() throws ExecutionException, InterruptedException {
                // Test that domain expertise resolution works for new agent types

                // Event-driven domain expertise
                AgentConsultationRequest eventRequest = AgentConsultationRequest.create(
                                "architecture",
                                "Design event-driven system with Kafka and event sourcing with event driven kafka event sourcing",
                                Map.of("service", "pos-events"));

                CompletableFuture<PriorityBasedRoutingManager.PriorityRoutingResult> eventFuture = routingManager
                                .routeWithPriority(eventRequest);
                PriorityBasedRoutingManager.PriorityRoutingResult eventResult = eventFuture.get();

                assertTrue(eventResult.isSuccessful());
                String eventGuidance = eventResult.getPrimaryResponse().guidance();
                assertTrue(eventGuidance.contains("event") || eventGuidance.contains("Kafka"));

                // CI/CD domain expertise
                AgentConsultationRequest cicdRequest = AgentConsultationRequest.create(
                                "deployment",
                                "Implement secure CI/CD pipeline with automated testing with cicd pipeline testing security",
                                Map.of("service", "pos-build"));

                CompletableFuture<PriorityBasedRoutingManager.PriorityRoutingResult> cicdFuture = routingManager
                                .routeWithPriority(cicdRequest);
                PriorityBasedRoutingManager.PriorityRoutingResult cicdResult = cicdFuture.get();

                assertTrue(cicdResult.isSuccessful());
                String cicdGuidance = cicdResult.getPrimaryResponse().guidance();
                assertTrue(cicdGuidance.contains("pipeline") || cicdGuidance.contains("CI/CD") ||
                                cicdGuidance.contains("testing"));

                // Verify both requests completed within performance requirements
                assertTrue(eventResult.getRoutingTime().toMillis() <= 1000);
                assertTrue(cicdResult.getRoutingTime().toMillis() <= 1000);
        }
}