package com.positivity.agent.collaboration;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.context.EventDrivenContext;
import com.positivity.agent.context.CICDContext;
import com.positivity.agent.context.ConfigurationContext;
import com.positivity.agent.context.ResilienceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages context-aware guidance with session management integration
 * Implements context integrity rules and session management from requirements
 */
@Component
public class ContextAwareGuidanceManager {

    private static final Logger logger = LoggerFactory.getLogger(ContextAwareGuidanceManager.class);

    // Session context storage: sessionId -> context data
    private final Map<String, SessionContext> sessionContexts = new ConcurrentHashMap<>();

    // NEW: Specialized context storage for new agent domains (REQ-012, REQ-013,
    // REQ-014, REQ-015)
    private final Map<String, EventDrivenContext> eventDrivenContexts = new ConcurrentHashMap<>();
    private final Map<String, CICDContext> cicdContexts = new ConcurrentHashMap<>();
    private final Map<String, ConfigurationContext> configurationContexts = new ConcurrentHashMap<>();
    private final Map<String, ResilienceContext> resilienceContexts = new ConcurrentHashMap<>();

    // Context integrity validation rules - Enhanced for new agent types
    private final Set<String> requiredContextKeys = Set.of(
            "project-context", "architectural-decisions", "current-task", "domain-constraints",
            // NEW: Context keys for new specialized agents (REQ-012, REQ-013, REQ-014,
            // REQ-015)
            "event-driven-context", "cicd-context", "configuration-context", "resilience-context");

    // Context anchoring sources
    private final Set<String> contextSources = Set.of(
            ".ai/context.md", ".ai/glossary.md", ".ai/session.md");

    /**
     * Validate context completeness before providing guidance
     */
    public ContextValidationResult validateContext(AgentConsultationRequest request) {
        Instant start = Instant.now();

        List<String> missingInputs = new ArrayList<>();
        List<String> missingDecisions = new ArrayList<>();

        // Check for required context inputs
        Map<String, Object> context = request.context();
        for (String requiredKey : requiredContextKeys) {
            if (!context.containsKey(requiredKey) || context.get(requiredKey) == null) {
                missingInputs.add(requiredKey);
            }
        }

        // Check for session context if available
        String sessionId = extractSessionId(request);
        SessionContext sessionContext = sessionContexts.get(sessionId);

        if (sessionContext != null) {
            // Validate session context freshness
            if (sessionContext.isStale()) {
                missingInputs.add("stale-session-context");
            }

            // Check for architectural decisions in session
            if (sessionContext.getArchitecturalDecisions().isEmpty()) {
                missingDecisions.add("architectural-decisions");
            }
        }

        Duration validationTime = Duration.between(start, Instant.now());

        if (!missingInputs.isEmpty() || !missingDecisions.isEmpty()) {
            return ContextValidationResult.insufficient(missingInputs, missingDecisions, validationTime);
        }

        return ContextValidationResult.sufficient(validationTime);
    }

    /**
     * Enhance guidance response with context-aware information
     */
    public AgentGuidanceResponse enhanceWithContext(AgentGuidanceResponse originalResponse,
            AgentConsultationRequest request) {

        String sessionId = extractSessionId(request);
        SessionContext sessionContext = sessionContexts.get(sessionId);

        if (sessionContext == null) {
            // Create new session context
            sessionContext = createSessionContext(sessionId, request);
            sessionContexts.put(sessionId, sessionContext);
        }

        // Update session context with new findings
        updateSessionContext(sessionContext, originalResponse, request);

        // NEW: Update specialized contexts based on agent type (REQ-012.2, REQ-013.3)
        updateSpecializedContext(sessionId, originalResponse.agentId(), originalResponse);

        // Enhance guidance with contextual information (including new specialized
        // contexts)
        String enhancedGuidance = enhanceGuidanceWithSpecializedContext(originalResponse.guidance(), sessionContext,
                sessionId);

        // Add contextual recommendations
        List<String> enhancedRecommendations = enhanceRecommendationsWithContext(
                originalResponse.recommendations(), sessionContext);

        return AgentGuidanceResponse.success(
                originalResponse.requestId(),
                originalResponse.agentId(),
                enhancedGuidance,
                originalResponse.confidence(),
                enhancedRecommendations,
                originalResponse.processingTime());
    }

    /**
     * Get session context for an agent consultation
     */
    public Optional<SessionContext> getSessionContext(String sessionId) {
        return Optional.ofNullable(sessionContexts.get(sessionId));
    }

    /**
     * Update session context after significant progress
     */
    public void updateSessionProgress(String sessionId, String taskObjective,
            Map<String, Object> decisions, List<String> nextSteps) {
        SessionContext context = sessionContexts.get(sessionId);
        if (context != null) {
            context.updateProgress(taskObjective, decisions, nextSteps);
            logger.debug("Updated session context for {}: {}", sessionId, taskObjective);
        }
    }

    /**
     * Clean up stale session contexts (Enhanced for new context types - REQ-001.3)
     */
    public void cleanupStaleContexts() {
        Instant cutoff = Instant.now().minus(Duration.ofHours(2));

        // Clean up session contexts
        sessionContexts.entrySet().removeIf(entry -> {
            boolean isStale = entry.getValue().getLastUpdated().isBefore(cutoff);
            if (isStale) {
                logger.debug("Cleaned up stale session context: {}", entry.getKey());
            }
            return isStale;
        });

        // NEW: Clean up specialized contexts (REQ-012.2, REQ-013.3, REQ-014.2,
        // REQ-015.2)
        cleanupStaleSpecializedContexts(cutoff);
    }

    /**
     * NEW: Clean up stale specialized contexts
     */
    private void cleanupStaleSpecializedContexts(Instant cutoff) {
        // Clean up EventDrivenContexts
        eventDrivenContexts.entrySet().removeIf(entry -> {
            boolean isStale = entry.getValue().getLastUpdated().isBefore(cutoff);
            if (isStale) {
                logger.debug("Cleaned up stale EventDrivenContext: {}", entry.getKey());
            }
            return isStale;
        });

        // Clean up CICDContexts
        cicdContexts.entrySet().removeIf(entry -> {
            boolean isStale = entry.getValue().getLastUpdated().isBefore(cutoff);
            if (isStale) {
                logger.debug("Cleaned up stale CICDContext: {}", entry.getKey());
            }
            return isStale;
        });

        // Clean up ConfigurationContexts
        configurationContexts.entrySet().removeIf(entry -> {
            boolean isStale = entry.getValue().getLastUpdated().isBefore(cutoff);
            if (isStale) {
                logger.debug("Cleaned up stale ConfigurationContext: {}", entry.getKey());
            }
            return isStale;
        });

        // Clean up ResilienceContexts
        resilienceContexts.entrySet().removeIf(entry -> {
            boolean isStale = entry.getValue().getLastUpdated().isBefore(cutoff);
            if (isStale) {
                logger.debug("Cleaned up stale ResilienceContext: {}", entry.getKey());
            }
            return isStale;
        });
    }

    /**
     * Archive session context to permanent storage
     */
    public void archiveSessionContext(String sessionId) {
        SessionContext context = sessionContexts.remove(sessionId);
        if (context != null) {
            // In a real implementation, this would write to .ai/context.md
            logger.info("Archived session context for {}: {} decisions preserved",
                    sessionId, context.getArchitecturalDecisions().size());
        }

        // Archive specialized contexts as well
        archiveSpecializedContexts(sessionId);
    }

    /**
     * NEW: Get or create EventDrivenContext for a session (REQ-012.1)
     */
    public EventDrivenContext getOrCreateEventDrivenContext(String sessionId) {
        return eventDrivenContexts.computeIfAbsent(sessionId,
                id -> new EventDrivenContext(UUID.randomUUID().toString(), id));
    }

    /**
     * NEW: Get or create CICDContext for a session (REQ-013.1)
     */
    public CICDContext getOrCreateCICDContext(String sessionId) {
        return cicdContexts.computeIfAbsent(sessionId,
                id -> new CICDContext(UUID.randomUUID().toString(), id));
    }

    /**
     * NEW: Get or create ConfigurationContext for a session (REQ-014.1)
     */
    public ConfigurationContext getOrCreateConfigurationContext(String sessionId) {
        return configurationContexts.computeIfAbsent(sessionId,
                id -> new ConfigurationContext(UUID.randomUUID().toString(), id));
    }

    /**
     * NEW: Get or create ResilienceContext for a session (REQ-015.1)
     */
    public ResilienceContext getOrCreateResilienceContext(String sessionId) {
        return resilienceContexts.computeIfAbsent(sessionId,
                id -> new ResilienceContext(UUID.randomUUID().toString(), id));
    }

    /**
     * NEW: Update context based on agent type and guidance (REQ-001.3, REQ-012.2,
     * REQ-013.3)
     */
    public void updateSpecializedContext(String sessionId, String agentId, AgentGuidanceResponse response) {
        // Update EventDrivenContext for event-driven architecture agent
        if (agentId.contains("event-driven") || agentId.contains("EventDriven")) {
            EventDrivenContext context = getOrCreateEventDrivenContext(sessionId);
            updateEventDrivenContextFromGuidance(context, response);
        }

        // Update CICDContext for CI/CD pipeline agent
        if (agentId.contains("cicd") || agentId.contains("CICD") || agentId.contains("pipeline")) {
            CICDContext context = getOrCreateCICDContext(sessionId);
            updateCICDContextFromGuidance(context, response);
        }

        // Update ConfigurationContext for configuration management agent
        if (agentId.contains("configuration") || agentId.contains("config")) {
            ConfigurationContext context = getOrCreateConfigurationContext(sessionId);
            updateConfigurationContextFromGuidance(context, response);
        }

        // Update ResilienceContext for resilience engineering agent
        if (agentId.contains("resilience") || agentId.contains("circuit") || agentId.contains("retry")) {
            ResilienceContext context = getOrCreateResilienceContext(sessionId);
            updateResilienceContextFromGuidance(context, response);
        }
    }

    /**
     * NEW: Share context between agents (REQ-001.3)
     */
    public Map<String, Object> getSharedContextForAgent(String sessionId, String agentId) {
        Map<String, Object> sharedContext = new HashMap<>();

        // Always include session context
        SessionContext sessionContext = sessionContexts.get(sessionId);
        if (sessionContext != null) {
            sharedContext.put("session", sessionContext);
        }

        // Include relevant specialized contexts based on agent type
        if (agentId.contains("event-driven") || agentId.contains("EventDriven")) {
            EventDrivenContext eventContext = eventDrivenContexts.get(sessionId);
            if (eventContext != null) {
                sharedContext.put("event-driven", eventContext);
            }
        }

        if (agentId.contains("cicd") || agentId.contains("CICD") || agentId.contains("pipeline")) {
            CICDContext cicdContext = cicdContexts.get(sessionId);
            if (cicdContext != null) {
                sharedContext.put("cicd", cicdContext);
            }
        }

        if (agentId.contains("configuration") || agentId.contains("config")) {
            ConfigurationContext configContext = configurationContexts.get(sessionId);
            if (configContext != null) {
                sharedContext.put("configuration", configContext);
            }
        }

        if (agentId.contains("resilience") || agentId.contains("circuit") || agentId.contains("retry")) {
            ResilienceContext resilienceContext = resilienceContexts.get(sessionId);
            if (resilienceContext != null) {
                sharedContext.put("resilience", resilienceContext);
            }
        }

        // Cross-domain context sharing for integration scenarios
        if (agentId.contains("integration") || agentId.contains("architecture")) {
            // Share all contexts for integration and architecture agents
            EventDrivenContext eventContext = eventDrivenContexts.get(sessionId);
            CICDContext cicdContext = cicdContexts.get(sessionId);
            ConfigurationContext configContext = configurationContexts.get(sessionId);
            ResilienceContext resilienceContext = resilienceContexts.get(sessionId);

            if (eventContext != null)
                sharedContext.put("event-driven", eventContext);
            if (cicdContext != null)
                sharedContext.put("cicd", cicdContext);
            if (configContext != null)
                sharedContext.put("configuration", configContext);
            if (resilienceContext != null)
                sharedContext.put("resilience", resilienceContext);
        }

        return sharedContext;
    }

    /**
     * NEW: Clean up specialized contexts (REQ-001.3)
     */
    private void archiveSpecializedContexts(String sessionId) {
        // Archive and remove specialized contexts
        EventDrivenContext eventContext = eventDrivenContexts.remove(sessionId);
        CICDContext cicdContext = cicdContexts.remove(sessionId);
        ConfigurationContext configContext = configurationContexts.remove(sessionId);
        ResilienceContext resilienceContext = resilienceContexts.remove(sessionId);

        // Log archival
        if (eventContext != null) {
            logger.info("Archived EventDrivenContext for {}: {} brokers, {} schemas",
                    sessionId, eventContext.getMessageBrokers().size(), eventContext.getEventSchemas().size());
        }
        if (cicdContext != null) {
            logger.info("Archived CICDContext for {}: {} build tools, {} deployment strategies",
                    sessionId, cicdContext.getBuildTools().size(), cicdContext.getDeploymentStrategies().size());
        }
        if (configContext != null) {
            logger.info("Archived ConfigurationContext for {}: {} config sources, {} feature flags",
                    sessionId, configContext.getConfigSources().size(), configContext.getFeatureFlags().size());
        }
        if (resilienceContext != null) {
            logger.info("Archived ResilienceContext for {}: {} circuit breakers, {} retry patterns",
                    sessionId, resilienceContext.getCircuitBreakers().size(),
                    resilienceContext.getRetryPatterns().size());
        }
    }

    private String extractSessionId(AgentConsultationRequest request) {
        return (String) request.context().getOrDefault("session-id", "default-session");
    }

    private SessionContext createSessionContext(String sessionId, AgentConsultationRequest request) {
        return new SessionContext(
                sessionId,
                (String) request.context().getOrDefault("task-objective", "Unknown task"),
                new HashMap<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                Instant.now());
    }

    private void updateSessionContext(SessionContext context, AgentGuidanceResponse response,
            AgentConsultationRequest request) {
        // Update architectural decisions
        if (response.agentId().contains("architecture")) {
            context.addArchitecturalDecision("agent-guidance", response.guidance());
        }

        // Update file paths accessed
        String filePath = (String) request.context().get("file-path");
        if (filePath != null) {
            context.addAccessedFile(filePath);
        }

        // Update integration points
        if (request.domain().equals("integration")) {
            context.addIntegrationPoint(response.agentId() + ": " + response.guidance());
        }

        context.setLastUpdated(Instant.now());
    }

    private String enhanceGuidanceWithContext(String originalGuidance, SessionContext context) {
        StringBuilder enhanced = new StringBuilder();

        // Add context header
        enhanced.append("## Context-Aware Guidance\n\n");

        // Add session information
        enhanced.append("**Session Context**: ").append(context.getSessionId()).append("\n");
        enhanced.append("**Current Task**: ").append(context.getTaskObjective()).append("\n\n");

        // Add architectural decisions context
        if (!context.getArchitecturalDecisions().isEmpty()) {
            enhanced.append("**Related Architectural Decisions**:\n");
            context.getArchitecturalDecisions()
                    .forEach((key, value) -> enhanced.append("- ").append(key).append(": ").append(value).append("\n"));
            enhanced.append("\n");
        }

        // NEW: Enhanced context for specialized agents (REQ-012, REQ-013, REQ-014,
        // REQ-015)

        // Event-driven architecture context
        if (context.hasEventDrivenContext()) {
            enhanced.append("**Event-Driven Architecture Context**:\n");
            enhanced.append("- Message Brokers: ").append(context.getMessageBrokers()).append("\n");
            enhanced.append("- Event Schemas: ").append(context.getEventSchemas()).append("\n");
            enhanced.append("- Event Handlers: ").append(context.getEventHandlers()).append("\n\n");
        }

        // CI/CD pipeline context
        if (context.hasCICDContext()) {
            enhanced.append("**CI/CD Pipeline Context**:\n");
            enhanced.append("- Build Tools: ").append(context.getBuildTools()).append("\n");
            enhanced.append("- Deployment Strategies: ").append(context.getDeploymentStrategies()).append("\n");
            enhanced.append("- Security Scanning: ").append(context.getSecurityScanning()).append("\n\n");
        }

        // Configuration management context
        if (context.hasConfigurationContext()) {
            enhanced.append("**Configuration Management Context**:\n");
            enhanced.append("- Config Sources: ").append(context.getConfigSources()).append("\n");
            enhanced.append("- Feature Flags: ").append(context.getFeatureFlags()).append("\n");
            enhanced.append("- Secrets Management: ").append(context.getSecretsManagement()).append("\n\n");
        }

        // Resilience engineering context
        if (context.hasResilienceContext()) {
            enhanced.append("**Resilience Engineering Context**:\n");
            enhanced.append("- Circuit Breakers: ").append(context.getCircuitBreakers()).append("\n");
            enhanced.append("- Retry Patterns: ").append(context.getRetryPatterns()).append("\n");
            enhanced.append("- Chaos Engineering: ").append(context.getChaosEngineering()).append("\n\n");
        }

        // Add integration points context
        if (!context.getIntegrationPoints().isEmpty()) {
            enhanced.append("**Integration Points to Consider**:\n");
            context.getIntegrationPoints().forEach(point -> enhanced.append("- ").append(point).append("\n"));
            enhanced.append("\n");
        }

        // Add original guidance
        enhanced.append("## Agent Guidance\n\n");
        enhanced.append(originalGuidance);

        return enhanced.toString();
    }

    /**
     * NEW: Enhanced guidance with specialized context integration (REQ-001.3,
     * REQ-012.2, REQ-013.3)
     */
    private String enhanceGuidanceWithSpecializedContext(String originalGuidance, SessionContext sessionContext,
            String sessionId) {
        StringBuilder enhanced = new StringBuilder();

        // Add context header
        enhanced.append("## Context-Aware Guidance\n\n");

        // Add session information
        enhanced.append("**Session Context**: ").append(sessionContext.getSessionId()).append("\n");
        enhanced.append("**Current Task**: ").append(sessionContext.getTaskObjective()).append("\n\n");

        // Add architectural decisions context
        if (!sessionContext.getArchitecturalDecisions().isEmpty()) {
            enhanced.append("**Related Architectural Decisions**:\n");
            sessionContext.getArchitecturalDecisions()
                    .forEach((key, value) -> enhanced.append("- ").append(key).append(": ").append(value).append("\n"));
            enhanced.append("\n");
        }

        // NEW: Enhanced context using specialized context models (REQ-012, REQ-013,
        // REQ-014, REQ-015)

        // Event-driven architecture context
        EventDrivenContext eventContext = eventDrivenContexts.get(sessionId);
        if (eventContext != null && eventContext.isValid()) {
            enhanced.append("**Event-Driven Architecture Context**:\n");
            enhanced.append("- Message Brokers: ").append(eventContext.getMessageBrokers()).append("\n");
            enhanced.append("- Event Schemas: ").append(eventContext.getEventSchemas()).append("\n");
            enhanced.append("- Event Handlers: ").append(eventContext.getEventHandlers()).append("\n");
            if (eventContext.hasEventSourcing()) {
                enhanced.append("- Event Sourcing: ").append(eventContext.getEventStores()).append("\n");
                enhanced.append("- Projections: ").append(eventContext.getProjections()).append("\n");
                enhanced.append("- Sagas: ").append(eventContext.getSagas()).append("\n");
            }
            if (eventContext.hasResiliencePatterns()) {
                enhanced.append("- Error Handling: ").append(eventContext.getErrorHandlingStrategies()).append("\n");
                enhanced.append("- Dead Letter Queues: ").append(eventContext.getDeadLetterQueues().keySet())
                        .append("\n");
            }
            enhanced.append("\n");
        }

        // CI/CD pipeline context
        CICDContext cicdContext = cicdContexts.get(sessionId);
        if (cicdContext != null && cicdContext.isValid()) {
            enhanced.append("**CI/CD Pipeline Context**:\n");
            enhanced.append("- Build Tools: ").append(cicdContext.getBuildTools()).append("\n");
            enhanced.append("- Deployment Strategies: ").append(cicdContext.getDeploymentStrategies()).append("\n");
            if (cicdContext.hasSecurityIntegration()) {
                enhanced.append("- Security Scanning: ").append(cicdContext.getSecurityScanners()).append("\n");
                enhanced.append("- Vulnerability Checks: ").append(cicdContext.getVulnerabilityChecks()).append("\n");
            }
            if (cicdContext.hasTestingAutomation()) {
                enhanced.append("- Testing Frameworks: ").append(cicdContext.getTestingFrameworks()).append("\n");
                enhanced.append("- Test Types: ").append(cicdContext.getTestTypes()).append("\n");
            }
            enhanced.append("- Orchestration Tools: ").append(cicdContext.getOrchestrationTools()).append("\n");
            enhanced.append("\n");
        }

        // Configuration management context
        ConfigurationContext configContext = configurationContexts.get(sessionId);
        if (configContext != null && configContext.isValid()) {
            enhanced.append("**Configuration Management Context**:\n");
            enhanced.append("- Config Sources: ").append(configContext.getConfigSources()).append("\n");
            if (configContext.hasFeatureFlags()) {
                enhanced.append("- Feature Flags: ").append(configContext.getFeatureFlags()).append("\n");
                enhanced.append("- Rollout Strategies: ").append(configContext.getRolloutStrategies()).append("\n");
            }
            if (configContext.hasSecretsManagement()) {
                enhanced.append("- Secrets Management: ").append(configContext.getSecretsManagers()).append("\n");
                enhanced.append("- Rotation Policies: ").append(configContext.getRotationPolicies()).append("\n");
            }
            if (configContext.hasEnvironmentIsolation()) {
                enhanced.append("- Environments: ").append(configContext.getEnvironments()).append("\n");
                enhanced.append("- Config Profiles: ").append(configContext.getConfigProfiles()).append("\n");
            }
            enhanced.append("\n");
        }

        // Resilience engineering context
        ResilienceContext resilienceContext = resilienceContexts.get(sessionId);
        if (resilienceContext != null && resilienceContext.isValid()) {
            enhanced.append("**Resilience Engineering Context**:\n");
            if (resilienceContext.hasCircuitBreakers()) {
                enhanced.append("- Circuit Breakers: ").append(resilienceContext.getCircuitBreakers()).append("\n");
                enhanced.append("- Failure Thresholds: ").append(resilienceContext.getFailureThresholds().keySet())
                        .append("\n");
            }
            if (resilienceContext.hasRetryMechanisms()) {
                enhanced.append("- Retry Patterns: ").append(resilienceContext.getRetryPatterns()).append("\n");
                enhanced.append("- Backoff Strategies: ").append(resilienceContext.getBackoffStrategies()).append("\n");
            }
            if (resilienceContext.hasBulkheadPatterns()) {
                enhanced.append("- Bulkhead Patterns: ").append(resilienceContext.getBulkheadPatterns()).append("\n");
                enhanced.append("- Thread Pools: ").append(resilienceContext.getThreadPools()).append("\n");
            }
            if (resilienceContext.hasChaosEngineering()) {
                enhanced.append("- Chaos Experiments: ").append(resilienceContext.getChaosExperiments()).append("\n");
                enhanced.append("- Resilience Tests: ").append(resilienceContext.getResilienceTests()).append("\n");
            }
            enhanced.append("\n");
        }

        // Add integration points context
        if (!sessionContext.getIntegrationPoints().isEmpty()) {
            enhanced.append("**Integration Points to Consider**:\n");
            sessionContext.getIntegrationPoints().forEach(point -> enhanced.append("- ").append(point).append("\n"));
            enhanced.append("\n");
        }

        // Add original guidance
        enhanced.append("## Agent Guidance\n\n");
        enhanced.append(originalGuidance);

        return enhanced.toString();
    }

    private List<String> enhanceRecommendationsWithContext(List<String> originalRecommendations,
            SessionContext context) {
        List<String> enhanced = new ArrayList<>(originalRecommendations);

        // Add context-specific recommendations
        if (!context.getAccessedFiles().isEmpty()) {
            enhanced.add("Review recent file changes: " + String.join(", ", context.getAccessedFiles()));
        }

        if (!context.getNextSteps().isEmpty()) {
            enhanced.add("Continue with planned next steps: " + String.join(", ", context.getNextSteps()));
        }

        // NEW: Enhanced recommendations for specialized agents (REQ-012, REQ-013,
        // REQ-014, REQ-015)

        // Event-driven architecture recommendations
        if (context.hasEventDrivenContext()) {
            enhanced.add("Consider event schema versioning and backward compatibility");
            enhanced.add("Implement idempotent event handlers for reliability");
            enhanced.add("Use dead letter queues for failed event processing");
            if (context.getMessageBrokers().contains("kafka")) {
                enhanced.add("Configure Kafka partitioning strategy for scalability");
            }
        }

        // CI/CD pipeline recommendations
        if (context.hasCICDContext()) {
            enhanced.add("Integrate security scanning (SAST/DAST) in pipeline");
            enhanced.add("Implement proper artifact management and versioning");
            enhanced.add("Use deployment strategies (blue-green/canary) for zero-downtime");
            if (context.getBuildTools().contains("maven")) {
                enhanced.add("Optimize Maven build with multi-stage Docker builds");
            }
        }

        // Configuration management recommendations
        if (context.hasConfigurationContext()) {
            enhanced.add("Implement configuration validation and health checks");
            enhanced.add("Use feature flags for gradual rollouts");
            enhanced.add("Secure secrets with proper rotation policies");
            if (context.getConfigSources().contains("spring-cloud-config")) {
                enhanced.add("Configure Spring Cloud Config with encryption");
            }
        }

        // Resilience engineering recommendations
        if (context.hasResilienceContext()) {
            enhanced.add("Implement circuit breakers for external dependencies");
            enhanced.add("Use bulkhead patterns for resource isolation");
            enhanced.add("Configure proper timeout and retry policies");
            if (context.getCircuitBreakers().contains("resilience4j")) {
                enhanced.add("Monitor Resilience4j metrics and adjust thresholds");
            }
        }

        return enhanced;
    }

    /**
     * NEW: Update EventDrivenContext from agent guidance (REQ-012.2)
     */
    private void updateEventDrivenContextFromGuidance(EventDrivenContext context, AgentGuidanceResponse response) {
        String guidance = response.guidance().toLowerCase();

        // Extract message brokers
        if (guidance.contains("kafka")) {
            context.addMessageBroker("kafka", Map.of("type", "distributed-streaming"));
        }
        if (guidance.contains("rabbitmq")) {
            context.addMessageBroker("rabbitmq", Map.of("type", "message-queue"));
        }
        if (guidance.contains("sns") || guidance.contains("sqs")) {
            context.addMessageBroker("aws-sns-sqs", Map.of("type", "cloud-messaging"));
        }

        // Extract event patterns
        if (guidance.contains("event sourcing")) {
            context.addEventStore("event-store");
        }
        if (guidance.contains("saga")) {
            context.addSaga("saga-pattern");
        }
        if (guidance.contains("idempotent")) {
            context.addEventHandler("idempotent-handler", "idempotency-key");
        }
        if (guidance.contains("dead letter")) {
            context.addDeadLetterQueue("failed-events", "dlq");
        }
    }

    /**
     * NEW: Update CICDContext from agent guidance (REQ-013.3)
     */
    private void updateCICDContextFromGuidance(CICDContext context, AgentGuidanceResponse response) {
        String guidance = response.guidance().toLowerCase();

        // Extract build tools
        if (guidance.contains("maven")) {
            context.addBuildTool("maven", Map.of("type", "java-build"));
        }
        if (guidance.contains("gradle")) {
            context.addBuildTool("gradle", Map.of("type", "java-build"));
        }
        if (guidance.contains("docker")) {
            context.addBuildTool("docker", Map.of("type", "containerization"));
        }

        // Extract deployment strategies
        if (guidance.contains("blue-green")) {
            context.addDeploymentStrategy("blue-green", Map.of("type", "zero-downtime"));
        }
        if (guidance.contains("canary")) {
            context.addDeploymentStrategy("canary", Map.of("type", "gradual-rollout"));
        }
        if (guidance.contains("rolling")) {
            context.addDeploymentStrategy("rolling", Map.of("type", "incremental"));
        }

        // Extract security scanning
        if (guidance.contains("sast")) {
            context.addSecurityScanner("sast", "static-analysis");
        }
        if (guidance.contains("dast")) {
            context.addSecurityScanner("dast", "dynamic-analysis");
        }
        if (guidance.contains("dependency scan")) {
            context.addVulnerabilityCheck("dependency-scan");
        }

        // Extract orchestration tools
        if (guidance.contains("jenkins")) {
            context.addOrchestrationTool("jenkins", Map.of("type", "ci-server"));
        }
        if (guidance.contains("github actions")) {
            context.addOrchestrationTool("github-actions", Map.of("type", "cloud-ci"));
        }
        if (guidance.contains("gitlab")) {
            context.addOrchestrationTool("gitlab-ci", Map.of("type", "integrated-ci"));
        }
    }

    /**
     * NEW: Update ConfigurationContext from agent guidance (REQ-014.2)
     */
    private void updateConfigurationContextFromGuidance(ConfigurationContext context, AgentGuidanceResponse response) {
        String guidance = response.guidance().toLowerCase();

        // Extract configuration sources
        if (guidance.contains("spring cloud config")) {
            context.addConfigSource("spring-cloud-config", Map.of("type", "centralized-config"));
        }
        if (guidance.contains("consul")) {
            context.addConfigSource("consul", Map.of("type", "service-discovery-config"));
        }
        if (guidance.contains("etcd")) {
            context.addConfigSource("etcd", Map.of("type", "distributed-config"));
        }

        // Extract feature flags
        if (guidance.contains("feature flag") || guidance.contains("feature toggle")) {
            context.addFeatureFlag("feature-toggles", Map.of("type", "runtime-flags"));
        }
        if (guidance.contains("gradual rollout")) {
            context.addRolloutStrategy("gradual-rollout");
        }
        if (guidance.contains("canary release")) {
            context.addRolloutStrategy("canary-release");
        }

        // Extract secrets management
        if (guidance.contains("aws secrets manager")) {
            context.addSecretsManager("aws-secrets-manager", Map.of("type", "cloud-secrets"));
        }
        if (guidance.contains("vault")) {
            context.addSecretsManager("hashicorp-vault", Map.of("type", "enterprise-secrets"));
        }
        if (guidance.contains("kubernetes secret")) {
            context.addSecretsManager("kubernetes-secrets", Map.of("type", "k8s-secrets"));
        }

        // Extract environments
        if (guidance.contains("development")) {
            context.addEnvironment("development", Map.of("profile", "dev"));
        }
        if (guidance.contains("staging")) {
            context.addEnvironment("staging", Map.of("profile", "stage"));
        }
        if (guidance.contains("production")) {
            context.addEnvironment("production", Map.of("profile", "prod"));
        }
    }

    /**
     * NEW: Update ResilienceContext from agent guidance (REQ-015.2)
     */
    private void updateResilienceContextFromGuidance(ResilienceContext context, AgentGuidanceResponse response) {
        String guidance = response.guidance().toLowerCase();

        // Extract circuit breakers
        if (guidance.contains("resilience4j")) {
            context.addCircuitBreaker("resilience4j", Map.of("type", "modern-circuit-breaker"));
        }
        if (guidance.contains("hystrix")) {
            context.addCircuitBreaker("hystrix", Map.of("type", "netflix-circuit-breaker"));
        }
        if (guidance.contains("spring cloud circuit breaker")) {
            context.addCircuitBreaker("spring-cloud-cb", Map.of("type", "spring-circuit-breaker"));
        }

        // Extract retry patterns
        if (guidance.contains("exponential backoff")) {
            context.addRetryPattern("exponential-backoff", Map.of("type", "backoff-retry"));
            context.addBackoffStrategy("exponential");
        }
        if (guidance.contains("jitter")) {
            context.addBackoffStrategy("jitter");
        }
        if (guidance.contains("retry limit")) {
            context.addRetryPolicy("default", "max-attempts-3");
        }

        // Extract bulkhead patterns
        if (guidance.contains("bulkhead")) {
            context.addBulkheadPattern("thread-pool-isolation", Map.of("type", "resource-isolation"));
        }
        if (guidance.contains("thread pool")) {
            context.addThreadPool("isolated-thread-pool");
        }

        // Extract chaos engineering
        if (guidance.contains("chaos monkey")) {
            context.addChaosExperiment("chaos-monkey", Map.of("type", "random-failure"));
        }
        if (guidance.contains("failure injection")) {
            context.addChaosExperiment("failure-injection", Map.of("type", "controlled-failure"));
        }
        if (guidance.contains("resilience test")) {
            context.addResilienceTest("resilience-validation");
        }

        // Extract health monitoring
        if (guidance.contains("health check")) {
            context.addHealthCheck("endpoint-health", Map.of("type", "http-health"));
        }
        if (guidance.contains("sli") || guidance.contains("slo")) {
            context.addSliSloDefinition("service", "availability-99.9");
        }
    }

    /**
     * Result of context validation
     */
    public static class ContextValidationResult {
        private final boolean isSufficient;
        private final List<String> missingInputs;
        private final List<String> missingDecisions;
        private final Duration validationTime;

        private ContextValidationResult(boolean isSufficient, List<String> missingInputs,
                List<String> missingDecisions, Duration validationTime) {
            this.isSufficient = isSufficient;
            this.missingInputs = missingInputs;
            this.missingDecisions = missingDecisions;
            this.validationTime = validationTime;
        }

        public static ContextValidationResult sufficient(Duration validationTime) {
            return new ContextValidationResult(true, List.of(), List.of(), validationTime);
        }

        public static ContextValidationResult insufficient(List<String> missingInputs,
                List<String> missingDecisions,
                Duration validationTime) {
            return new ContextValidationResult(false, missingInputs, missingDecisions, validationTime);
        }

        public boolean isSufficient() {
            return isSufficient;
        }

        public List<String> getMissingInputs() {
            return missingInputs;
        }

        public List<String> getMissingDecisions() {
            return missingDecisions;
        }

        public Duration getValidationTime() {
            return validationTime;
        }

        public String getInsufficientContextMessage() {
            if (isSufficient)
                return "";

            StringBuilder message = new StringBuilder("Context insufficient – re-anchor needed\n");

            if (!missingInputs.isEmpty()) {
                message.append("Missing inputs: ").append(String.join(", ", missingInputs)).append("\n");
            }

            if (!missingDecisions.isEmpty()) {
                message.append("Missing decisions: ").append(String.join(", ", missingDecisions)).append("\n");
            }

            return message.toString();
        }
    }

    /**
     * Session context data structure
     */
    public static class SessionContext {
        private final String sessionId;
        private String taskObjective;
        private final Map<String, String> architecturalDecisions;
        private final List<String> accessedFiles;
        private final List<String> integrationPoints;
        private final List<String> nextSteps;
        private Instant lastUpdated;

        // NEW: Enhanced context for specialized agents (REQ-012, REQ-013, REQ-014,
        // REQ-015)
        private final List<String> messageBrokers;
        private final List<String> eventSchemas;
        private final List<String> eventHandlers;
        private final List<String> buildTools;
        private final List<String> deploymentStrategies;
        private final List<String> securityScanning;
        private final List<String> configSources;
        private final List<String> featureFlags;
        private final List<String> secretsManagement;
        private final List<String> circuitBreakers;
        private final List<String> retryPatterns;
        private final List<String> chaosEngineering;

        public SessionContext(String sessionId, String taskObjective,
                Map<String, String> architecturalDecisions,
                List<String> accessedFiles, List<String> integrationPoints,
                List<String> nextSteps, Instant lastUpdated) {
            this.sessionId = sessionId;
            this.taskObjective = taskObjective;
            this.architecturalDecisions = architecturalDecisions;
            this.accessedFiles = accessedFiles;
            this.integrationPoints = integrationPoints;
            this.nextSteps = nextSteps;
            this.lastUpdated = lastUpdated;

            // Initialize new context collections
            this.messageBrokers = new ArrayList<>();
            this.eventSchemas = new ArrayList<>();
            this.eventHandlers = new ArrayList<>();
            this.buildTools = new ArrayList<>();
            this.deploymentStrategies = new ArrayList<>();
            this.securityScanning = new ArrayList<>();
            this.configSources = new ArrayList<>();
            this.featureFlags = new ArrayList<>();
            this.secretsManagement = new ArrayList<>();
            this.circuitBreakers = new ArrayList<>();
            this.retryPatterns = new ArrayList<>();
            this.chaosEngineering = new ArrayList<>();
        }

        public boolean isStale() {
            return lastUpdated.isBefore(Instant.now().minus(Duration.ofMinutes(30)));
        }

        public void updateProgress(String taskObjective, Map<String, Object> decisions, List<String> nextSteps) {
            this.taskObjective = taskObjective;
            decisions.forEach((key, value) -> this.architecturalDecisions.put(key, value.toString()));
            this.nextSteps.clear();
            this.nextSteps.addAll(nextSteps);
            this.lastUpdated = Instant.now();
        }

        public void addArchitecturalDecision(String key, String decision) {
            this.architecturalDecisions.put(key, decision);
        }

        public void addAccessedFile(String filePath) {
            if (!this.accessedFiles.contains(filePath)) {
                this.accessedFiles.add(filePath);
            }
        }

        public void addIntegrationPoint(String integrationPoint) {
            if (!this.integrationPoints.contains(integrationPoint)) {
                this.integrationPoints.add(integrationPoint);
            }
        }

        // NEW: Methods for specialized agent contexts (REQ-012, REQ-013, REQ-014,
        // REQ-015)

        // Event-driven context methods
        public boolean hasEventDrivenContext() {
            return !messageBrokers.isEmpty() || !eventSchemas.isEmpty() || !eventHandlers.isEmpty();
        }

        public void addMessageBroker(String broker) {
            if (!this.messageBrokers.contains(broker)) {
                this.messageBrokers.add(broker);
            }
        }

        public void addEventSchema(String schema) {
            if (!this.eventSchemas.contains(schema)) {
                this.eventSchemas.add(schema);
            }
        }

        public void addEventHandler(String handler) {
            if (!this.eventHandlers.contains(handler)) {
                this.eventHandlers.add(handler);
            }
        }

        // CI/CD context methods
        public boolean hasCICDContext() {
            return !buildTools.isEmpty() || !deploymentStrategies.isEmpty() || !securityScanning.isEmpty();
        }

        public void addBuildTool(String tool) {
            if (!this.buildTools.contains(tool)) {
                this.buildTools.add(tool);
            }
        }

        public void addDeploymentStrategy(String strategy) {
            if (!this.deploymentStrategies.contains(strategy)) {
                this.deploymentStrategies.add(strategy);
            }
        }

        public void addSecurityScanning(String scanning) {
            if (!this.securityScanning.contains(scanning)) {
                this.securityScanning.add(scanning);
            }
        }

        // Configuration context methods
        public boolean hasConfigurationContext() {
            return !configSources.isEmpty() || !featureFlags.isEmpty() || !secretsManagement.isEmpty();
        }

        public void addConfigSource(String source) {
            if (!this.configSources.contains(source)) {
                this.configSources.add(source);
            }
        }

        public void addFeatureFlag(String flag) {
            if (!this.featureFlags.contains(flag)) {
                this.featureFlags.add(flag);
            }
        }

        public void addSecretsManagement(String secrets) {
            if (!this.secretsManagement.contains(secrets)) {
                this.secretsManagement.add(secrets);
            }
        }

        // Resilience context methods
        public boolean hasResilienceContext() {
            return !circuitBreakers.isEmpty() || !retryPatterns.isEmpty() || !chaosEngineering.isEmpty();
        }

        public void addCircuitBreaker(String breaker) {
            if (!this.circuitBreakers.contains(breaker)) {
                this.circuitBreakers.add(breaker);
            }
        }

        public void addRetryPattern(String pattern) {
            if (!this.retryPatterns.contains(pattern)) {
                this.retryPatterns.add(pattern);
            }
        }

        public void addChaosEngineering(String chaos) {
            if (!this.chaosEngineering.contains(chaos)) {
                this.chaosEngineering.add(chaos);
            }
        }

        // Getters
        public String getSessionId() {
            return sessionId;
        }

        public String getTaskObjective() {
            return taskObjective;
        }

        public Map<String, String> getArchitecturalDecisions() {
            return architecturalDecisions;
        }

        public List<String> getAccessedFiles() {
            return accessedFiles;
        }

        public List<String> getIntegrationPoints() {
            return integrationPoints;
        }

        public List<String> getNextSteps() {
            return nextSteps;
        }

        public Instant getLastUpdated() {
            return lastUpdated;
        }

        public void setLastUpdated(Instant lastUpdated) {
            this.lastUpdated = lastUpdated;
        }

        // NEW: Getters for specialized agent contexts (REQ-012, REQ-013, REQ-014,
        // REQ-015)

        // Event-driven context getters
        public List<String> getMessageBrokers() {
            return messageBrokers;
        }

        public List<String> getEventSchemas() {
            return eventSchemas;
        }

        public List<String> getEventHandlers() {
            return eventHandlers;
        }

        // CI/CD context getters
        public List<String> getBuildTools() {
            return buildTools;
        }

        public List<String> getDeploymentStrategies() {
            return deploymentStrategies;
        }

        public List<String> getSecurityScanning() {
            return securityScanning;
        }

        // Configuration context getters
        public List<String> getConfigSources() {
            return configSources;
        }

        public List<String> getFeatureFlags() {
            return featureFlags;
        }

        public List<String> getSecretsManagement() {
            return secretsManagement;
        }

        // Resilience context getters
        public List<String> getCircuitBreakers() {
            return circuitBreakers;
        }

        public List<String> getRetryPatterns() {
            return retryPatterns;
        }

        public List<String> getChaosEngineering() {
            return chaosEngineering;
        }
    }
}