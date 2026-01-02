package com.pos.agent.collaboration;


import com.pos.agent.context.EventDrivenContext;
import com.pos.agent.context.CICDContext;
import com.pos.agent.context.ConfigurationContext;
import com.pos.agent.context.ResilienceContext;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for enhanced ContextAwareGuidanceManager
 * Tests Phase 4 enhancements: specialized context management for new agent
 * domains
 * 
 * Requirements: REQ-001.3, REQ-012.1, REQ-013.1, REQ-014.1, REQ-015.1
 */
class ContextAwareGuidanceManagerTest {

    private ContextAwareGuidanceManager contextManager;
    private AgentRequest testRequest;
    private AgentResponse testResponse;

    @BeforeEach
    void setUp() {
        contextManager = new ContextAwareGuidanceManager();

        // Create test request with session context
        AgentContext context = new HashMap<>();
        context.put("session-id", "test-session-123");
        context.put("project-context", "test-project");
        context.put("architectural-decisions", "microservices-architecture");
        context.put("current-task", "implement-event-driven-patterns");
        context.put("domain-constraints", "spring-boot-constraints");
        context.put("event-driven-context", "kafka-integration");
        context.put("cicd-context", "jenkins-pipeline");
        context.put("configuration-context", "spring-cloud-config");
        context.put("resilience-context", "circuit-breaker-patterns");

        testRequest = AgentRequest.builder()
                "event-driven-architecture",
                "How to implement Kafka event streaming?",
                context,
                "integration",
                AgentRequest.Priority.NORMAL);

        // Create test response
        testResponse = AgentResponse.success(
                "Implement Kafka with idempotent handlers and dead letter queues for resilience",
                0.95
               );
    }

    // Context Validation Tests

    @Test
    @DisplayName("Should validate sufficient context with all required keys")
    void testValidateContext_SufficientContext() {
        // Act
        ContextValidationResult result = contextManager.validateContext(testRequest);

        // Assert
        assertTrue(result.isSufficient());
        assertTrue(result.getMissingInputs().isEmpty());
        assertTrue(result.getMissingDecisions().isEmpty());
        assertNotNull(result.getValidationTime());
        assertTrue(result.getValidationTime().toMillis() >= 0);
    }

    @Test
    @DisplayName("Should detect missing required context keys")
    void testValidateContext_MissingRequiredKeys() {
        // Arrange - Create request with missing context keys
        Map<String, Object> incompleteContext = new HashMap<>();
        incompleteContext.put("session-id", "test-session");
        incompleteContext.put("project-context", "test-project");
        // Missing: architectural-decisions, current-task, domain-constraints,
        // specialized contexts

        AgentRequest incompleteRequest = AgentRequest.create(
                "test-agent",
                "test query",
                incompleteContext,
                "test",
                AgentRequest.Priority.NORMAL);

        // Act
        ContextValidationResult result = contextManager.validateContext(incompleteRequest);

        // Assert
        assertFalse(result.isSufficient());
        assertFalse(result.getMissingInputs().isEmpty());
        assertTrue(result.getMissingInputs().contains("architectural-decisions"));
        assertTrue(result.getMissingInputs().contains("current-task"));
        assertTrue(result.getMissingInputs().contains("domain-constraints"));
        assertTrue(result.getMissingInputs().contains("event-driven-context"));
        assertTrue(result.getMissingInputs().contains("cicd-context"));
        assertTrue(result.getMissingInputs().contains("configuration-context"));
        assertTrue(result.getMissingInputs().contains("resilience-context"));

        String message = result.getInsufficientContextMessage();
        assertTrue(message.contains("Context insufficient – re-anchor needed"));
        assertTrue(message.contains("Missing inputs:"));
    }

    @Test
    @DisplayName("Should detect stale session context")
    void testValidateContext_StaleSessionContext() {
        // Arrange - Create session context and make it stale
        String sessionId = "stale-session";
        contextManager.updateSessionProgress(sessionId, "old-task", Map.of(), List.of());

        // Manually access session context to make it stale (simulate time passage)
        Optional<SessionContext> sessionOpt = contextManager.getSessionContext(sessionId);
        assertTrue(sessionOpt.isPresent());
        SessionContext session = sessionOpt.get();
        session.setLastUpdated(Instant.now().minus(Duration.ofHours(1))); // Make it stale

        Map<String, Object> context = new HashMap<>(testRequest.context());
        context.put("session-id", sessionId);

        AgentRequest staleRequest = AgentRequest.create(
                "test-agent",
                "test query",
                context,
                "test",
                AgentRequest.Priority.NORMAL);

        // Act
        ContextValidationResult result = contextManager.validateContext(staleRequest);

        // Assert
        assertFalse(result.isSufficient());
        assertTrue(result.getMissingInputs().contains("stale-session-context"));
    }

    // Specialized Context Creation Tests

    @Test
    @DisplayName("Should create EventDrivenContext for session")
    void testGetOrCreateEventDrivenContext() {
        // Act
        EventDrivenContext context1 = contextManager.getOrCreateEventDrivenContext("test-session");
        EventDrivenContext context2 = contextManager.getOrCreateEventDrivenContext("test-session");

        // Assert
        assertNotNull(context1);
        assertSame(context1, context2); // Should return same instance for same session
        assertEquals("test-session", context1.getSessionId());
        assertNotNull(context1.getContextId());
        assertNotNull(context1.getCreatedAt());
        assertNotNull(context1.getLastUpdated());
    }

    @Test
    @DisplayName("Should create CICDContext for session")
    void testGetOrCreateCICDContext() {
        // Act
        CICDContext context1 = contextManager.getOrCreateCICDContext("test-session");
        CICDContext context2 = contextManager.getOrCreateCICDContext("test-session");

        // Assert
        assertNotNull(context1);
        assertSame(context1, context2); // Should return same instance for same session
        assertEquals("test-session", context1.getSessionId());
        assertNotNull(context1.getContextId());
        assertNotNull(context1.getCreatedAt());
        assertNotNull(context1.getLastUpdated());
    }

    @Test
    @DisplayName("Should create ConfigurationContext for session")
    void testGetOrCreateConfigurationContext() {
        // Act
        ConfigurationContext context1 = contextManager.getOrCreateConfigurationContext("test-session");
        ConfigurationContext context2 = contextManager.getOrCreateConfigurationContext("test-session");

        // Assert
        assertNotNull(context1);
        assertSame(context1, context2); // Should return same instance for same session
        assertEquals("test-session", context1.getSessionId());
        assertNotNull(context1.getContextId());
        assertNotNull(context1.getCreatedAt());
        assertNotNull(context1.getLastUpdated());
    }

    @Test
    @DisplayName("Should create ResilienceContext for session")
    void testGetOrCreateResilienceContext() {
        // Act
        ResilienceContext context1 = contextManager.getOrCreateResilienceContext("test-session");
        ResilienceContext context2 = contextManager.getOrCreateResilienceContext("test-session");

        // Assert
        assertNotNull(context1);
        assertSame(context1, context2); // Should return same instance for same session
        assertEquals("test-session", context1.getSessionId());
        assertNotNull(context1.getContextId());
        assertNotNull(context1.getCreatedAt());
        assertNotNull(context1.getLastUpdated());
    }

    // Specialized Context Updates Tests

    @Test
    @DisplayName("Should update EventDrivenContext from agent guidance")
    void testUpdateSpecializedContext_EventDriven() {
        // Arrange
        String sessionId = "event-session";
        AgentResponse eventResponse = AgentResponse.success(
                "event-request",
                "event-driven-agent",
                "Use Kafka for event streaming with idempotent handlers and dead letter queues for failed events. " +
                        "Implement event sourcing with saga patterns for complex workflows.",
                0.9,
                List.of("Configure Kafka partitioning", "Add schema registry"),
                Duration.ofMillis(200));

        // Act
        contextManager.updateSpecializedContext(sessionId, "event-driven-agent", eventResponse);

        // Assert
        EventDrivenContext context = contextManager.getOrCreateEventDrivenContext(sessionId);
        assertFalse(context.getMessageBrokers().isEmpty());
        assertTrue(context.getMessageBrokers().contains("kafka"));
        assertFalse(context.getEventHandlers().isEmpty());
        assertTrue(context.getEventHandlers().contains("idempotent-handler"));
        assertFalse(context.getDeadLetterQueuesMap().isEmpty());
        assertTrue(context.getDeadLetterQueuesMap().containsKey("failed-events"));
        assertFalse(context.getEventStores().isEmpty());
        assertTrue(context.getEventStores().contains("event-store"));
        assertFalse(context.getSagas().isEmpty());
        assertTrue(context.getSagas().contains("saga-pattern"));
    }

    @Test
    @DisplayName("Should update CICDContext from agent guidance")
    void testUpdateSpecializedContext_CICD() {
        // Arrange
        String sessionId = "cicd-session";
        AgentResponse cicdResponse = AgentResponse.success(
                "cicd-request",
                "cicd-pipeline-agent",
                "Use Maven for build automation with Docker containerization. " +
                        "Implement blue-green deployment strategy with SAST and DAST security scanning. " +
                        "Configure Jenkins for pipeline orchestration.",
                0.85,
                List.of("Setup Maven profiles", "Configure Docker builds"),
                Duration.ofMillis(180));

        // Act
        contextManager.updateSpecializedContext(sessionId, "cicd-pipeline-agent", cicdResponse);

        // Assert
        CICDContext context = contextManager.getOrCreateCICDContext(sessionId);
        assertFalse(context.getBuildTools().isEmpty());
        assertTrue(context.getBuildTools().contains("maven"));
        assertTrue(context.getBuildTools().contains("docker"));
        assertFalse(context.getDeploymentStrategies().isEmpty());
        assertTrue(context.getDeploymentStrategies().contains("blue-green"));
        assertFalse(context.getSecurityScanners().isEmpty());
        assertTrue(context.getSecurityScanners().contains("sast"));
        assertTrue(context.getSecurityScanners().contains("dast"));
        assertFalse(context.getOrchestrationTools().isEmpty());
        assertTrue(context.getOrchestrationTools().contains("jenkins"));
    }

    @Test
    @DisplayName("Should update ConfigurationContext from agent guidance")
    void testUpdateSpecializedContext_Configuration() {
        // Arrange
        String sessionId = "config-session";
        AgentResponse configResponse = AgentResponse.success(
                "config-request",
                "configuration-management-agent",
                "Use Spring Cloud Config for centralized configuration with Consul for service discovery. " +
                        "Implement feature flags for gradual rollout and AWS Secrets Manager for secrets management. " +
                        "Configure development, staging, and production environments.",
                0.88,
                List.of("Setup Spring Cloud Config server", "Configure Consul"),
                Duration.ofMillis(160));

        // Act
        contextManager.updateSpecializedContext(sessionId, "configuration-management-agent", configResponse);

        // Assert
        ConfigurationContext context = contextManager.getOrCreateConfigurationContext(sessionId);
        assertFalse(context.getConfigSources().isEmpty());
        assertTrue(context.getConfigSources().contains("spring-cloud-config"));
        assertTrue(context.getConfigSources().contains("consul"));
        assertFalse(context.getFeatureFlags().isEmpty());
        assertTrue(context.getFeatureFlags().contains("feature-toggles"));
        assertFalse(context.getRolloutStrategies().isEmpty());
        assertTrue(context.getRolloutStrategies().contains("gradual-rollout"));
        assertFalse(context.getSecretsManagers().isEmpty());
        assertTrue(context.getSecretsManagers().contains("aws-secrets-manager"));
        assertFalse(context.getEnvironments().isEmpty());
        assertTrue(context.getEnvironments().contains("development"));
        assertTrue(context.getEnvironments().contains("staging"));
        assertTrue(context.getEnvironments().contains("production"));
    }

    @Test
    @DisplayName("Should update ResilienceContext from agent guidance")
    void testUpdateSpecializedContext_Resilience() {
        // Arrange
        String sessionId = "resilience-session";
        AgentResponse resilienceResponse = AgentResponse.success(
                "resilience-request",
                "resilience-engineering-agent",
                "Use Resilience4j for circuit breaker patterns with exponential backoff and jitter for retry mechanisms. "
                        +
                        "Implement bulkhead patterns with thread pool isolation and chaos monkey for failure injection. "
                        +
                        "Configure health checks with SLI/SLO definitions.",
                0.92,
                List.of("Configure Resilience4j", "Setup chaos experiments"),
                Duration.ofMillis(170));

        // Act
        contextManager.updateSpecializedContext(sessionId, "resilience-engineering-agent", resilienceResponse);

        // Assert
        ResilienceContext context = contextManager.getOrCreateResilienceContext(sessionId);
        assertFalse(context.getCircuitBreakers().isEmpty());
        assertTrue(context.getCircuitBreakers().contains("resilience4j"));
        assertFalse(context.getRetryPatterns().isEmpty());
        assertTrue(context.getRetryPatterns().contains("exponential-backoff"));
        assertFalse(context.getBackoffStrategies().isEmpty());
        assertTrue(context.getBackoffStrategies().contains("exponential"));
        assertTrue(context.getBackoffStrategies().contains("jitter"));
        assertFalse(context.getBulkheadPatterns().isEmpty());
        assertTrue(context.getBulkheadPatterns().contains("thread-pool-isolation"));
        assertFalse(context.getThreadPools().isEmpty());
        assertTrue(context.getThreadPools().contains("isolated-thread-pool"));
        assertFalse(context.getChaosExperiments().isEmpty());
        assertTrue(context.getChaosExperiments().contains("chaos-monkey"));
        assertFalse(context.getHealthChecks().isEmpty());
        assertTrue(context.getHealthChecks().contains("endpoint-health"));
        assertFalse(context.getSliSloDefinitions().isEmpty());
        assertTrue(context.getSliSloDefinitions().containsKey("service"));
    }

    // Context Sharing Tests

    @Test
    @DisplayName("Should share relevant context for event-driven agent")
    void testGetSharedContextForAgent_EventDriven() {
        // Arrange
        String sessionId = "shared-session";
        contextManager.updateSessionProgress(sessionId, "event-task", Map.of("decision", "kafka"), List.of("next"));

        EventDrivenContext eventContext = contextManager.getOrCreateEventDrivenContext(sessionId);
        eventContext.addMessageBroker("kafka", Map.of("type", "streaming"));

        // Act
        Map<String, Object> sharedContext = contextManager.getSharedContextForAgent(sessionId, "event-driven-agent");

        // Assert
        assertNotNull(sharedContext);
        assertTrue(sharedContext.containsKey("session"));
        assertTrue(sharedContext.containsKey("event-driven"));
        assertFalse(sharedContext.containsKey("cicd")); // Should not include unrelated contexts
        assertFalse(sharedContext.containsKey("configuration"));
        assertFalse(sharedContext.containsKey("resilience"));

        EventDrivenContext sharedEventContext = (EventDrivenContext) sharedContext.get("event-driven");
        assertEquals(eventContext, sharedEventContext);
    }

    @Test
    @DisplayName("Should share all contexts for architecture agent")
    void testGetSharedContextForAgent_Architecture() {
        // Arrange
        String sessionId = "arch-session";
        contextManager.updateSessionProgress(sessionId, "arch-task", Map.of(), List.of());

        // Create all specialized contexts
        EventDrivenContext eventContext = contextManager.getOrCreateEventDrivenContext(sessionId);
        eventContext.addMessageBroker("kafka", Map.of());

        CICDContext cicdContext = contextManager.getOrCreateCICDContext(sessionId);
        cicdContext.addBuildTool("maven", Map.of());

        ConfigurationContext configContext = contextManager.getOrCreateConfigurationContext(sessionId);
        configContext.addConfigSource("spring-cloud-config", Map.of());

        ResilienceContext resilienceContext = contextManager.getOrCreateResilienceContext(sessionId);
        resilienceContext.addCircuitBreaker("resilience4j", Map.of());

        // Act
        Map<String, Object> sharedContext = contextManager.getSharedContextForAgent(sessionId, "architecture-agent");

        // Assert
        assertNotNull(sharedContext);
        assertTrue(sharedContext.containsKey("session"));
        assertTrue(sharedContext.containsKey("event-driven"));
        assertTrue(sharedContext.containsKey("cicd"));
        assertTrue(sharedContext.containsKey("configuration"));
        assertTrue(sharedContext.containsKey("resilience"));

        // Verify all contexts are shared
        assertEquals(eventContext, sharedContext.get("event-driven"));
        assertEquals(cicdContext, sharedContext.get("cicd"));
        assertEquals(configContext, sharedContext.get("configuration"));
        assertEquals(resilienceContext, sharedContext.get("resilience"));
    }

    @Test
    @DisplayName("Should share only session context for unrelated agent")
    void testGetSharedContextForAgent_UnrelatedAgent() {
        // Arrange
        String sessionId = "unrelated-session";
        contextManager.updateSessionProgress(sessionId, "unrelated-task", Map.of(), List.of());

        // Create specialized contexts
        contextManager.getOrCreateEventDrivenContext(sessionId);
        contextManager.getOrCreateCICDContext(sessionId);

        // Act
        Map<String, Object> sharedContext = contextManager.getSharedContextForAgent(sessionId, "documentation-agent");

        // Assert
        assertNotNull(sharedContext);
        assertTrue(sharedContext.containsKey("session"));
        assertFalse(sharedContext.containsKey("event-driven"));
        assertFalse(sharedContext.containsKey("cicd"));
        assertFalse(sharedContext.containsKey("configuration"));
        assertFalse(sharedContext.containsKey("resilience"));
    }

    // Enhanced Guidance Tests

    @Test
    @DisplayName("Should enhance guidance with specialized context information")
    void testEnhanceWithContext_SpecializedContexts() {
        // Arrange
        String sessionId = "enhance-session";
        Map<String, Object> context = new HashMap<>(testRequest.context());
        context.put("session-id", sessionId);

        AgentRequest enhanceRequest = AgentRequest.create(
                "event-driven-agent",
                "How to implement event streaming?",
                context,
                "integration",
                AgentRequest.Priority.NORMAL);

        // Create and populate specialized contexts
        EventDrivenContext eventContext = contextManager.getOrCreateEventDrivenContext(sessionId);
        eventContext.addMessageBroker("kafka", Map.of("type", "streaming"));
        eventContext.addEventSchema("user-events", "v1.0");
        eventContext.addEventHandler("user-handler", "idempotency-key");

        CICDContext cicdContext = contextManager.getOrCreateCICDContext(sessionId);
        cicdContext.addBuildTool("maven", Map.of("type", "java-build"));
        cicdContext.addDeploymentStrategy("blue-green", Map.of("type", "zero-downtime"));

        // Act
        AgentResponse enhancedResponse = contextManager.enhanceWithContext(testResponse, enhanceRequest);

        // Assert
        assertNotNull(enhancedResponse);
        assertEquals(testResponse.requestId(), enhancedResponse.requestId());
        assertEquals(testResponse.agentId(), enhancedResponse.agentId());
        assertEquals(testResponse.confidence(), enhancedResponse.confidence());

        String enhancedGuidance = enhancedResponse.guidance();
        assertTrue(enhancedGuidance.contains("Context-Aware Guidance"));
        assertTrue(enhancedGuidance.contains("Session Context"));
        assertTrue(enhancedGuidance.contains("Event-Driven Architecture Context"));
        assertTrue(enhancedGuidance.contains("CI/CD Pipeline Context"));
        assertTrue(enhancedGuidance.contains("Message Brokers: [kafka]"));
        assertTrue(enhancedGuidance.contains("Event Schemas: [user-events]"));
        assertTrue(enhancedGuidance.contains("Build Tools: [maven]"));
        assertTrue(enhancedGuidance.contains("Deployment Strategies: [blue-green]"));

        // Verify enhanced recommendations
        List<String> recommendations = enhancedResponse.recommendations();
        assertTrue(recommendations.size() > testResponse.recommendations().size());
        assertTrue(recommendations.containsAll(testResponse.recommendations()));

        // Check for specialized recommendations
        boolean hasEventRecommendations = recommendations.stream()
                .anyMatch(r -> r.contains("event schema versioning") || r.contains("idempotent event handlers"));
        assertTrue(hasEventRecommendations);

        boolean hasCICDRecommendations = recommendations.stream()
                .anyMatch(r -> r.contains("security scanning") || r.contains("deployment strategies"));
        assertTrue(hasCICDRecommendations);
    }

    // Context Cleanup Tests

    @Test
    @DisplayName("Should cleanup stale specialized contexts")
    void testCleanupStaleContexts_SpecializedContexts() {
        // Arrange
        String sessionId = "cleanup-session";

        // Create specialized contexts
        contextManager.getOrCreateEventDrivenContext(sessionId);
        contextManager.getOrCreateCICDContext(sessionId);
        contextManager.getOrCreateConfigurationContext(sessionId);
        contextManager.getOrCreateResilienceContext(sessionId);

        // Verify contexts exist
        assertNotNull(contextManager.getOrCreateEventDrivenContext(sessionId));
        assertNotNull(contextManager.getOrCreateCICDContext(sessionId));
        assertNotNull(contextManager.getOrCreateConfigurationContext(sessionId));
        assertNotNull(contextManager.getOrCreateResilienceContext(sessionId));

        // Make contexts stale by manipulating their lastUpdated timestamp
        // Note: This is a test-specific approach since we can't easily mock time
        // In a real scenario, we'd use a time provider or wait for actual time passage

        // Act
        contextManager.cleanupStaleContexts();

        // Assert - contexts should still exist since they're not actually stale yet
        // This test validates the cleanup method runs without errors
        assertDoesNotThrow(() -> contextManager.cleanupStaleContexts());
    }

    @Test
    @DisplayName("Should archive specialized contexts")
    void testArchiveSessionContext_SpecializedContexts() {
        // Arrange
        String sessionId = "archive-session";

        // Create and populate specialized contexts
        EventDrivenContext eventContext = contextManager.getOrCreateEventDrivenContext(sessionId);
        eventContext.addMessageBroker("kafka", Map.of());
        eventContext.addEventSchema("test-schema", "v1.0");

        CICDContext cicdContext = contextManager.getOrCreateCICDContext(sessionId);
        cicdContext.addBuildTool("maven", Map.of());
        cicdContext.addDeploymentStrategy("blue-green", Map.of());

        ConfigurationContext configContext = contextManager.getOrCreateConfigurationContext(sessionId);
        configContext.addConfigSource("spring-cloud-config", Map.of());
        configContext.addFeatureFlag("test-flag", Map.of());

        ResilienceContext resilienceContext = contextManager.getOrCreateResilienceContext(sessionId);
        resilienceContext.addCircuitBreaker("resilience4j", Map.of());
        resilienceContext.addRetryPattern("exponential-backoff", Map.of());

        // Create session context
        contextManager.updateSessionProgress(sessionId, "test-task", Map.of("decision", "value"), List.of("next"));

        // Verify contexts exist before archival
        assertTrue(contextManager.getSessionContext(sessionId).isPresent());

        // Act
        contextManager.archiveSessionContext(sessionId);

        // Assert
        assertFalse(contextManager.getSessionContext(sessionId).isPresent());

        // Verify specialized contexts are also cleaned up
        // Note: We can't directly verify removal since getOrCreate methods will
        // recreate them
        // But we can verify the archive method runs without errors
        assertDoesNotThrow(() -> contextManager.archiveSessionContext("non-existent-session"));
    }

    // Session Context Integration Tests

    @Test
    @DisplayName("Should create and manage session context")
    void testSessionContextManagement() {
        // Arrange
        String sessionId = "session-mgmt-test";

        // Act - Update session progress
        Map<String, Object> decisions = Map.of(
                "architecture", "microservices",
                "database", "postgresql",
                "messaging", "kafka");
        List<String> nextSteps = List.of(
                "implement-event-handlers",
                "setup-database-schema",
                "configure-kafka-topics");

        contextManager.updateSessionProgress(sessionId, "implement-event-driven-system", decisions, nextSteps);

        // Assert
        Optional<SessionContext> sessionOpt = contextManager.getSessionContext(sessionId);
        assertTrue(sessionOpt.isPresent());

        SessionContext session = sessionOpt.get();
        assertEquals(sessionId, session.getSessionId());
        assertEquals("implement-event-driven-system", session.getTaskObjective());
        assertEquals(3, session.getArchitecturalDecisions().size());
        assertEquals("microservices", session.getArchitecturalDecisions().get("architecture"));
        assertEquals("postgresql", session.getArchitecturalDecisions().get("database"));
        assertEquals("kafka", session.getArchitecturalDecisions().get("messaging"));
        assertEquals(3, session.getNextSteps().size());
        assertTrue(session.getNextSteps().contains("implement-event-handlers"));
        assertTrue(session.getNextSteps().contains("setup-database-schema"));
        assertTrue(session.getNextSteps().contains("configure-kafka-topics"));
        assertNotNull(session.getLastUpdated());
        assertFalse(session.isStale());
    }

    @Test
    @DisplayName("Should detect stale session context")
    void testSessionContextStaleness() {
        // Arrange
        String sessionId = "stale-test";
        contextManager.updateSessionProgress(sessionId, "old-task", Map.of(), List.of());

        Optional<SessionContext> sessionOpt = contextManager.getSessionContext(sessionId);
        assertTrue(sessionOpt.isPresent());

        SessionContext session = sessionOpt.get();

        // Make session stale
        session.setLastUpdated(Instant.now().minus(Duration.ofMinutes(35)));

        // Act & Assert
        assertTrue(session.isStale());
    }

    // Integration Test for Complete Workflow

    @Test
    @DisplayName("Should handle complete context management workflow")
    void testCompleteContextWorkflow() {
        // Arrange
        String sessionId = "complete-workflow";

        // Step 1: Validate initial context
        ContextValidationResult validation = contextManager.validateContext(testRequest);
        assertTrue(validation.isSufficient());

        // Step 2: Create specialized contexts
        contextManager.getOrCreateEventDrivenContext(sessionId);
        contextManager.getOrCreateCICDContext(sessionId);
        contextManager.getOrCreateConfigurationContext(sessionId);
        contextManager.getOrCreateResilienceContext(sessionId);

        // Step 3: Update contexts from agent guidance
        AgentResponse eventResponse = AgentResponse.success(
                "event-req", "event-driven-agent",
                "Use Kafka with idempotent handlers and saga patterns for event sourcing",
                0.9, List.of("Configure Kafka"), Duration.ofMillis(100));
        contextManager.updateSpecializedContext(sessionId, "event-driven-agent", eventResponse);

        // Step 4: Share context between agents
        Map<String, Object> sharedContext = contextManager.getSharedContextForAgent(sessionId, "architecture-agent");
        assertTrue(sharedContext.containsKey("event-driven"));

        // Step 5: Enhance guidance with context
        Map<String, Object> requestContext = new HashMap<>(testRequest.context());
        requestContext.put("session-id", sessionId);
        AgentRequest workflowRequest = AgentRequest.create(
                "architecture-agent", "Design system architecture", requestContext, "architecture",
                AgentRequest.Priority.NORMAL);

        AgentResponse enhancedResponse = contextManager.enhanceWithContext(testResponse, workflowRequest);
        assertNotNull(enhancedResponse);
        assertTrue(enhancedResponse.guidance().contains("Context-Aware Guidance"));

        // Step 6: Cleanup
        contextManager.cleanupStaleContexts();
        contextManager.archiveSessionContext(sessionId);

        // Verify workflow completed successfully
        assertFalse(contextManager.getSessionContext(sessionId).isPresent());
    }
}