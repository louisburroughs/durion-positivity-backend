package com.pos.agent.core;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.CICDContext;
import com.pos.agent.context.ConfigurationContext;
import com.pos.agent.context.EventDrivenContext;
import com.pos.agent.context.ResilienceContext;
import com.pos.agent.framework.audit.AuditTrailManager;
import com.pos.agent.framework.model.AgentType;
import com.pos.agent.impl.StoryValidationAgent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages agent lifecycle and request processing.
 * Handles security validation, routing, and response generation.
 * 
 * Context-aware guidance manager that maintains session context
 * and specialized domain contexts across agent consultations.
 * 
 * Requirements: REQ-001.3, REQ-012.1, REQ-013.1, REQ-014.1, REQ-015.1
 */
public class AgentManager implements AgentRegistry {
    private final AuditTrailManager auditTrailManager;
    private final List<Agent> registeredAgents;
     static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);
    private static final Set<String> REQUIRED_CONTEXT_KEYS = Set.of(
            "session-id", "project-context", "architectural-decisions",
            "current-task", "domain-constraints", "event-driven-context",
            "cicd-context", "configuration-context", "resilience-context");

            //Maps contexts by session id
    private final Map<String, SessionContext> sessionContexts = new ConcurrentHashMap<>();
    private final Map<String, EventDrivenContext> eventDrivenContexts = new ConcurrentHashMap<>();
    private final Map<String, CICDContext> cicdContexts = new ConcurrentHashMap<>();
    private final Map<String, ConfigurationContext> configurationContexts = new ConcurrentHashMap<>();
    private final Map<String, ResilienceContext> resilienceContexts = new ConcurrentHashMap<>();

    public AgentManager() {
        this(new AuditTrailManager());
    }

    public AgentManager(AuditTrailManager auditTrailManager) {
        this.auditTrailManager = auditTrailManager;
        this.registeredAgents = new ArrayList<>();

        // Register default agents
        registerAgent(new StoryValidationAgent());
    }

    /**
     * Register an agent with the manager.
     *
     * @param agent The agent to register
     */
    public void registerAgent(Agent agent) {
        registeredAgents.add(agent);
    }

    /**
     * Processes an agent request with security validation.
     *
     * @param request the agent request to process
     * @return the agent response
     */
    public AgentResponse processRequest(AgentRequest request) {
        long startTime = System.currentTimeMillis();
        String userId = SecurityValidation.getInstance().extractUserId(request);
        String agentType = request.getAgentContext().getAgentDomain();
        boolean success = false;

        try {
            // Validate security context
            if (request.getSecurityContext() != null) {
                if (!SecurityValidation.getInstance().validateSecurityContext(request.getSecurityContext())) {
                    // Record failed authentication attempt
                    recordAuditEntry(agentType, userId, "AUTHENTICATION_FAILED", false);

                    return AgentResponse.builder()
                            .success(false)
                            .errorMessage("Authentication failed: Invalid security credentials")
                            .processingTimeMs(System.currentTimeMillis() - startTime)
                            .build();
                }

            }

            // Try to find a registered agent that can handle the request
            // TODO implement real agent activity and routing logic
            Agent handlingAgent = findAgentForRequest(request);

            // Check role-based authorization using agent's declared requirements
            if (handlingAgent != null
                    && !SecurityValidation.getInstance().validateAuthorization(request, handlingAgent)) {
                // Record failed authorization attempt
                recordAuditEntry(agentType, userId, "AUTHORIZATION_FAILED", false);

                return AgentResponse.builder()
                        .success(false)
                        .errorMessage("Authorization failed: Insufficient permissions for " + agentType)
                        .processingTimeMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            if (handlingAgent != null) {
                // Delegate to the registered agent
                AgentResponse response = handlingAgent.processRequest(request);
                success = response.isSuccess();

                // Record request processing
                recordAuditEntry(agentType, userId,
                        success ? "REQUEST_PROCESSED" : "REQUEST_FAILED", success);

                return response;
            }

            long processingTime = System.currentTimeMillis() - startTime;

            // Generate output based on request type
            String output = generateOutput(request, handlingAgent);
            success = true;

            // Record successful request processing
            recordAuditEntry(agentType, userId, "REQUEST_PROCESSED", true);

            return AgentResponse.builder()
                    .success(true)
                    .status("SUCCESS")
                    .output(output)
                    .confidence(0.95)
                    .processingTimeMs(processingTime)
                    .build();
        } catch (Exception e) {
            // Record failed request processing
            recordAuditEntry(agentType, userId, "REQUEST_FAILED", false);
            throw e;
        }
    }

    /**
     * Find an agent that can handle the given request.
     * For StoryValidationAgent, checks if it can handle the request
     * based on activation conditions.
     *
     * @param request The request to find an agent for
     * @return The agent that can handle the request, or null if none found
     */
    private Agent findAgentForRequest(AgentRequest request) {
        for (Agent agent : registeredAgents) {
            // Special handling for StoryValidationAgent
            if (agent instanceof StoryValidationAgent) {
                // Always delegate story domain requests to StoryValidationAgent
                // The agent will determine if it can handle based on activation conditions
                if (request.getAgentContext() instanceof com.pos.agent.context.AgentContext) {
                    com.pos.agent.context.AgentContext ctx = (com.pos.agent.context.AgentContext) request
                            .getAgentContext();
                    if ("story".equals(ctx.getAgentDomain())) {
                        return agent;
                    }
                }
            }
        }
        return null;
    }

    // TODO: Performance Optimization Step 2 - Optimize audit trail recording
    // Make audit recording asynchronous using a queue (e.g., BlockingQueue or
    // Disruptor)
    // to avoid blocking request processing. Consider batching multiple audit
    // entries
    // and writing them in bulk. Make audit recording configurable/optional for
    // performance tests.
    private void recordAuditEntry(String agentType, String userId, String action, boolean success) {
        AuditTrailManager.AuditEntry entry = new AuditTrailManager.AuditEntry(
                agentType,
                userId,
                action,
                success);
        auditTrailManager.recordAuditEntry(entry);
    }

    public AuditTrailManager getAuditTrailManager() {
        return auditTrailManager;
    }

    private String generateOutput(AgentRequest request, Agent agent) {
        // TODO Generate a real query from the request context

        String query = "general request";

        // Try to extract query from context
        AgentContext contextObj = request.getAgentContext();
        if (contextObj != null) {

            // For documentation requests, try to extract meaningful info from AgentContext
            // The context contains "focus" property which hints at the request type
            query = "documentation synchronization request";

        }

        return agent.generateOutput(query);

    }

    @Override
    public boolean unregisterAgent(Agent agent) {
        registeredAgents.remove(agent);
        return true;
    }

    @Override
    public void clearAgents() {
        registeredAgents.clear();
    }

    @Override
    public List<Agent> listAgents() {
        return Collections.unmodifiableList(registeredAgents);
    }

    @Override
    public CompletableFuture<AgentResponse> consultBestAgent(AgentRequest request) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'consultBestAgent'");
    }

    @Override
    public RegistryHealthStatus getHealthStatus() {
        return new RegistryHealthStatus(registeredAgents.size(),
                registeredAgents.stream().filter(Agent::isHealthy).count());
    }

    @Override
    public List<Agent> getAgentsWithCapabilities(Set<String> capabilities) {
        return registeredAgents.stream()
                .filter(agent -> {
                    List<String> agentCapabilities = agent.getCapabilities();
                    return agentCapabilities != null &&
                            agentCapabilities.stream().anyMatch(capabilities::contains);
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<Agent> getAgentsForTechnicalDomain(String technicalDomain) {
        return registeredAgents.stream()
                .filter(agent -> {
                    AgentType agentTechnicalDomain = agent.getTechnicalDomain();
                    return agentTechnicalDomain != null && agentTechnicalDomain.equalsIgnoreCase(technicalDomain);
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<Agent> getAgentsForAgentType(AgentType searchAgentType) {
        return registeredAgents.stream()
                .filter(agent -> {
                    AgentType agentTechnicalDomain = agent.getTechnicalDomain();
                    return agentTechnicalDomain != null && agentTechnicalDomain.equals(searchAgentType);
                })
                .collect(Collectors.toList());
    }


    /**
     * Validates if request context is sufficient.
     */
    public ContextValidationResult validateContext(AgentRequest request) {
        Instant start = Instant.now();
        List<String> missing = new ArrayList<>();
        List<String> missingDecisions = new ArrayList<>();

        AgentContext context = request.getAgentContext();

        // Check required keys
        for (String key : REQUIRED_CONTEXT_KEYS) {
            if (!context.getProperties().containsKey(key)) {
                missing.add(key);
            }
        }

        // Check session staleness
        if (context.getProperties().containsKey("session-id")) {
            String sessionId = context.getProperties().get("session-id").toString();
            Optional<SessionContext> sessionOpt = getSessionContext(sessionId);
            if (sessionOpt.isPresent() && sessionOpt.get().isStale()) {
                missing.add("stale-session-context");
            }
        }

        Duration validationTime = Duration.between(start, Instant.now());
        boolean sufficient = missing.isEmpty() && missingDecisions.isEmpty();

        return new ContextValidationResult(sufficient, missing, missingDecisions, validationTime);
    }




    

    /**
     * Get or create EventDrivenContext for a session.
     */
    public EventDrivenContext getOrCreateEventDrivenContext(String sessionId) {
        return eventDrivenContexts.computeIfAbsent(sessionId,
                sid -> EventDrivenContext.builder().requestId(sid).build());
    }

    /**
     * Get or create CICDContext for a session.
     */
    public CICDContext getOrCreateCICDContext(String sessionId) {
        return cicdContexts.computeIfAbsent(sessionId,
                sid -> CICDContext.builder().requestId(sid).build());
    }

    /**
     * Get or create ConfigurationContext for a session.
     */
    public ConfigurationContext getOrCreateConfigurationContext(String sessionId) {
        return configurationContexts.computeIfAbsent(sessionId,
                sid -> ConfigurationContext.builder().requestId(sid).build());
    }

    /**
     * Get or create ResilienceContext for a session.
     */
    public ResilienceContext getOrCreateResilienceContext(String sessionId) {
        return resilienceContexts.computeIfAbsent(sessionId,
                sid -> ResilienceContext.builder().requestId(sid).build());
    }

    /**
     * Update session progress with task objectives and decisions.
     */
    public void updateSessionProgress(String sessionId, String taskObjective,
            Map<String, Object> decisions, List<String> nextSteps) {
        SessionContext session = sessionContexts.computeIfAbsent(sessionId, SessionContext::new);
        session.setTaskObjective(taskObjective);
        session.setArchitecturalDecisions(decisions);
        session.setNextSteps(nextSteps);
    }

    /**
     * Get session context.
     */
    public Optional<SessionContext> getSessionContext(String sessionId) {
        return Optional.ofNullable(sessionContexts.get(sessionId));
    }

    /**
     * Update specialized context from agent guidance.
     */
    public void updateSpecializedContext(String sessionId, String agentId, AgentResponse response) {
        String guidance = response.getOutput().toLowerCase();

        if (agentId.contains("event-driven")) {
            updateEventDrivenContext(sessionId, guidance);
        } else if (agentId.contains("cicd")) {
            updateCICDContext(sessionId, guidance);
        } else if (agentId.contains("configuration")) {
            updateConfigurationContext(sessionId, guidance);
        } else if (agentId.contains("resilience")) {
            updateResilienceContext(sessionId, guidance);
        }
    }

    private void updateEventDrivenContext(String sessionId, String guidance) {
        EventDrivenContext ctx = getOrCreateEventDrivenContext(sessionId);

        if (guidance.contains("kafka"))
            ctx.addMessageBroker("kafka", Map.of("type", "streaming"));
        if (guidance.contains("idempotent"))
            ctx.addEventHandler("idempotent-handler", "idempotency");
        if (guidance.contains("dead letter"))
            ctx.addDeadLetterQueue("failed-events", "dlq-config");
        if (guidance.contains("event sourcing") || guidance.contains("event store"))
            ctx.addEventStore("event-store");
        if (guidance.contains("saga"))
            ctx.addSaga("saga-pattern");
    }

    private void updateCICDContext(String sessionId, String guidance) {
        CICDContext ctx = getOrCreateCICDContext(sessionId);

        if (guidance.contains("maven"))
            ctx.addBuildTool("maven", Map.of("type", "java-build"));
        if (guidance.contains("docker"))
            ctx.addBuildTool("docker", Map.of("type", "containerization"));
        if (guidance.contains("blue-green"))
            ctx.addDeploymentStrategy("blue-green", Map.of("type", "zero-downtime"));
        if (guidance.contains("sast"))
            ctx.addSecurityScanner("sast", Map.of("type", "static-analysis"));
        if (guidance.contains("dast"))
            ctx.addSecurityScanner("dast", Map.of("type", "dynamic-analysis"));
        if (guidance.contains("jenkins"))
            ctx.addOrchestrationTool("jenkins", Map.of("type", "ci-server"));
    }

    private void updateConfigurationContext(String sessionId, String guidance) {
        ConfigurationContext ctx = getOrCreateConfigurationContext(sessionId);

        if (guidance.contains("spring cloud config"))
            ctx.addConfigSource("spring-cloud-config", Map.of());
        if (guidance.contains("consul"))
            ctx.addConfigSource("consul", Map.of());
        if (guidance.contains("feature flag"))
            ctx.addFeatureFlag("feature-toggles", Map.of());
        if (guidance.contains("gradual rollout"))
            ctx.addRolloutStrategy("gradual-rollout");
        if (guidance.contains("aws secrets manager"))
            ctx.addSecretsManager("aws-secrets-manager", Map.of());
        if (guidance.contains("development"))
            ctx.addEnvironment("development", Map.of());
        if (guidance.contains("staging"))
            ctx.addEnvironment("staging", Map.of());
        if (guidance.contains("production"))
            ctx.addEnvironment("production", Map.of());
    }

    private void updateResilienceContext(String sessionId, String guidance) {
        ResilienceContext ctx = getOrCreateResilienceContext(sessionId);

        if (guidance.contains("resilience4j") || guidance.contains("circuit breaker"))
            ctx.addCircuitBreaker("resilience4j", Map.of());
        if (guidance.contains("exponential backoff") || guidance.contains("retry"))
            ctx.addRetryPattern("exponential-backoff", Map.of());
        if (guidance.contains("exponential"))
            ctx.addBackoffStrategy("exponential");
        if (guidance.contains("jitter"))
            ctx.addBackoffStrategy("jitter");
        if (guidance.contains("bulkhead"))
            ctx.addBulkheadPattern("thread-pool-isolation");
        if (guidance.contains("thread pool"))
            ctx.addThreadPool("isolated-thread-pool");
        if (guidance.contains("chaos monkey") || guidance.contains("chaos"))
            ctx.addChaosExperiment("chaos-monkey");
        if (guidance.contains("health check"))
            ctx.addHealthCheck("endpoint-health");
        if (guidance.contains("sli") || guidance.contains("slo"))
            ctx.addSliSloDefinition("service", Map.of());
    }

    /**
     * Get shared context for a specific agent.
     */
    public Map<String, Object> getSharedContextForAgent(String sessionId, String agentId) {
        Map<String, Object> sharedContext = new HashMap<>();

        // Always include session context
        getSessionContext(sessionId).ifPresent(session -> sharedContext.put("session", session));

        // Include relevant specialized contexts based on agent type
        if (agentId.contains("event-driven")) {
            sharedContext.put("event-driven", getOrCreateEventDrivenContext(sessionId));
        } else if (agentId.contains("cicd")) {
            sharedContext.put("cicd", getOrCreateCICDContext(sessionId));
        } else if (agentId.contains("configuration")) {
            sharedContext.put("configuration", getOrCreateConfigurationContext(sessionId));
        } else if (agentId.contains("resilience")) {
            sharedContext.put("resilience", getOrCreateResilienceContext(sessionId));
        } else if (agentId.contains("architecture")) {
            // Architecture agent gets all contexts
            sharedContext.put("event-driven", getOrCreateEventDrivenContext(sessionId));
            sharedContext.put("cicd", getOrCreateCICDContext(sessionId));
            sharedContext.put("configuration", getOrCreateConfigurationContext(sessionId));
            sharedContext.put("resilience", getOrCreateResilienceContext(sessionId));
        }

        return sharedContext;
    }

    /**
     * Enhance guidance with context information.
     */
    public AgentResponse enhanceWithContext(AgentResponse response,
            AgentRequest request) {
        String sessionId = request.getAgentContext().getSessionId();

        StringBuilder enhancedGuidance = new StringBuilder();
        enhancedGuidance.append("=== Context-Aware Guidance ===\n\n");
        enhancedGuidance.append("Original Guidance:\n");
        enhancedGuidance.append(response.getOutput()).append("\n\n");

        // Add session context (create if missing to ensure visibility in guidance)
        SessionContext session = sessionContexts.computeIfAbsent(sessionId, SessionContext::new);
        enhancedGuidance.append("Session Context:\n");
        enhancedGuidance.append("- Task: ")
                .append(session.getTaskObjective() != null ? session.getTaskObjective() : "")
                .append("\n");
        enhancedGuidance.append("- Decisions: ")
                .append(session.getArchitecturalDecisions() != null ? session.getArchitecturalDecisions()
                        : java.util.Map.of())
                .append("\n");
        enhancedGuidance.append("- Next Steps: ")
                .append(session.getNextSteps() != null ? session.getNextSteps() : java.util.List.of())
                .append("\n\n");

        // Add specialized contexts
        EventDrivenContext eventCtx = eventDrivenContexts.get(sessionId);
        if (eventCtx != null && !eventCtx.getMessageBrokers().isEmpty()) {
            enhancedGuidance.append("Event-Driven Architecture Context:\n");
            enhancedGuidance.append("- Message Brokers: ").append(eventCtx.getMessageBrokers()).append("\n");
            enhancedGuidance.append("- Event Handlers: ").append(eventCtx.getEventHandlers()).append("\n");
            enhancedGuidance.append("- Event Schemas: ").append(eventCtx.getEventSchemas()).append("\n\n");
        }

        CICDContext cicdCtx = cicdContexts.get(sessionId);
        if (cicdCtx != null && !cicdCtx.getBuildTools().isEmpty()) {
            enhancedGuidance.append("CI/CD Pipeline Context:\n");
            enhancedGuidance.append("- Build Tools: ").append(cicdCtx.getBuildTools()).append("\n");
            enhancedGuidance.append("- Deployment Strategies: ").append(cicdCtx.getDeploymentStrategies())
                    .append("\n\n");
        }

        // Enhanced recommendations
        List<String> enhancedRecommendations = new ArrayList<>(response.getRecommendations());
        if (eventCtx != null && !eventCtx.getMessageBrokers().isEmpty()) {
            enhancedRecommendations.add("Consider event schema versioning for " + eventCtx.getMessageBrokers());
            enhancedRecommendations.add("Ensure idempotent event handlers for reliability");
        }
        if (cicdCtx != null && !cicdCtx.getBuildTools().isEmpty()) {
            enhancedRecommendations.add("Integrate security scanning in CI/CD pipeline");
            enhancedRecommendations.add("Consider deployment strategies: " + cicdCtx.getDeploymentStrategies());
        }

        return AgentResponse.success(
                enhancedGuidance.toString(),
                response.getConfidence());
    }

    /**
     * Cleanup stale contexts.
     */
    public void cleanupStaleContexts() {
        sessionContexts.entrySet().removeIf(entry -> entry.getValue().isStale());
    }

    /**
     * Archive session context.
     */
    public void archiveSessionContext(String sessionId) {
        sessionContexts.remove(sessionId);
        eventDrivenContexts.remove(sessionId);
        cicdContexts.remove(sessionId);
        configurationContexts.remove(sessionId);
        resilienceContexts.remove(sessionId);
    }

}
