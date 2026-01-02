package com.pos.agent.core;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.CICDContext;
import com.pos.agent.context.ConfigurationContext;
import com.pos.agent.context.EventDrivenContext;
import com.pos.agent.context.ResilienceContext;
import com.pos.agent.discovery.AgentDiscovery;
import com.pos.agent.discovery.CapabilityBasedDiscoveryStrategy;
import com.pos.agent.discovery.CompositeAgentDiscovery;
import com.pos.agent.discovery.DomainBasedDiscoveryStrategy;
import com.pos.agent.discovery.ObjectiveBasedDiscoveryStrategy;
import com.pos.agent.framework.audit.AuditTrailManager;
import com.pos.agent.framework.model.AgentType;
import com.pos.agent.framework.service.ServiceAgentMapping;
import com.pos.agent.impl.ArchitectureAgent;
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
import java.util.function.Supplier;
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
public class AgentManager implements AgentRegistry, ContextCoordinator {
    private final AuditTrailManager auditTrailManager;
    private final List<Agent> registeredAgents;
    private final AgentDiscovery agentDiscovery;
    private final SecurityValidator securityValidator;
    private final Supplier<Agent> fallbackAgentSupplier;

    static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);
    private static final Set<String> REQUIRED_CONTEXT_KEYS = Set.of(
            "session-id", "project-context", "architectural-decisions",
            "current-task", "domain-constraints", "event-driven-context",
            "cicd-context", "configuration-context", "resilience-context");

    // Maps contexts by session id
    private final Map<String, SessionContext> sessionContexts = new ConcurrentHashMap<>();

    /**
     * Creates an AgentManager with default collaborators.
     * Uses SecurityValidation singleton, CompositeAgentDiscovery, and ArchitectureAgent fallback.
     */
    public AgentManager() {
        this(new AuditTrailManager(), new ServiceAgentMapping());
    }

    /**
     * Creates an AgentManager with custom audit manager.
     * Uses default service mapping, SecurityValidation singleton, CompositeAgentDiscovery, and ArchitectureAgent fallback.
     */
    public AgentManager(AuditTrailManager auditTrailManager) {
        this(auditTrailManager, new ServiceAgentMapping());
    }

    /**
     * Creates an AgentManager with custom audit manager and service mapping.
     * Uses SecurityValidation singleton, CompositeAgentDiscovery, and ArchitectureAgent fallback.
     */
    public AgentManager(AuditTrailManager auditTrailManager, ServiceAgentMapping serviceMapping) {
        this(auditTrailManager, serviceMapping, new DefaultSecurityValidator(), 
             createDefaultDiscovery(serviceMapping), ArchitectureAgent::new);
    }

    /**
     * Full constructor accepting all collaborators for dependency injection.
     * Allows complete control over security validation, discovery, and fallback behavior.
     * 
     * @param auditTrailManager the audit manager for tracking request processing
     * @param serviceMapping the service-to-agent mapping configuration
     * @param securityValidator the security validator for authentication/authorization
     * @param agentDiscovery the discovery strategy for routing requests
     * @param fallbackAgentSupplier supplier for the fallback agent when discovery times out
     */
    public AgentManager(AuditTrailManager auditTrailManager, 
                       ServiceAgentMapping serviceMapping,
                       SecurityValidator securityValidator,
                       AgentDiscovery agentDiscovery,
                       Supplier<Agent> fallbackAgentSupplier) {
        this.auditTrailManager = auditTrailManager;
        this.registeredAgents = new ArrayList<>();
        this.agentDiscovery = agentDiscovery;
        this.securityValidator = securityValidator;
        this.fallbackAgentSupplier = fallbackAgentSupplier;

        // Register default agents
        registerAgent(new StoryValidationAgent());
    }

    /**
     * Creates the default composite discovery with all strategies.
     */
    private static AgentDiscovery createDefaultDiscovery(ServiceAgentMapping serviceMapping) {
        CompositeAgentDiscovery discovery = new CompositeAgentDiscovery();
        discovery.registerStrategy(new DomainBasedDiscoveryStrategy(serviceMapping));
        discovery.registerStrategy(new ObjectiveBasedDiscoveryStrategy());
        discovery.registerStrategy(new CapabilityBasedDiscoveryStrategy());
        return discovery;
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
        String userId = securityValidator.extractUserId(request);
        String agentType = request.getAgentContext().getAgentDomain();
        boolean success = false;

        try {
            // Validate security context
            if (request.getSecurityContext() != null) {
                if (!securityValidator.validateSecurityContext(request.getSecurityContext())) {
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

            Agent handlingAgent = consultBestAgent(request)
                    .completeOnTimeout(fallbackAgentSupplier.get(), 5000, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .join();

            // Check role-based authorization using agent's declared requirements
            if (handlingAgent != null
                    && !securityValidator.validateAuthorization(request, handlingAgent)) {
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
        AgentContext contextObj = request.getAgentContext();

        if (contextObj == null) {
            return agent.generateOutput("general request");
        }

        // Build a comprehensive query from available context
        StringBuilder queryBuilder = new StringBuilder();

        // Primary query source: objective, task, or focus
        Optional<String> primaryQuery = Optional.ofNullable(contextObj.getProperties().get("objective"))
                .or(() -> Optional.ofNullable(contextObj.getProperties().get("task")))
                .or(() -> Optional.ofNullable(contextObj.getProperties().get("focus")))
                .map(Object::toString);

        if (primaryQuery.isPresent()) {
            queryBuilder.append(primaryQuery.get());
        } else {
            queryBuilder.append("general request");
        }

        // Enhance query with domain context if available
        String domain = contextObj.getAgentDomain();
        if (domain != null && !domain.isEmpty() && !queryBuilder.toString().contains(domain)) {
            queryBuilder.append(" for ").append(domain);
        }

        // Add session context if available for continuity
        String sessionId = contextObj.getSessionId();
        if (sessionId != null) {
            getSessionContext(sessionId).ifPresent(session -> {
                if (session.getTaskObjective() != null && !session.getTaskObjective().isEmpty()) {
                    queryBuilder.append(" (Task: ").append(session.getTaskObjective()).append(")");
                }
            });
        }

        return agent.generateOutput(queryBuilder.toString());

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

    /**
     * Discovers the best agent for a given request using composite strategy
     * pattern.
     * Applies discovery strategies in priority order (domain-based >
     * objective-based > capability-based).
     * Filters results to only healthy agents.
     * 
     * @param request the agent request with domain and context information
     * @return CompletableFuture with the best agent, or empty if no match found
     */
    @Override
    public CompletableFuture<Agent> consultBestAgent(AgentRequest request) {
        return agentDiscovery.discoverBestAgent(request, registeredAgents)
                .thenApply(optionalAgent -> optionalAgent.orElse(null));
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
    public void updateSpecializedContext(String sessionId, Agent agent, AgentResponse response) {
        String guidance = response.getOutput().toLowerCase();
        agent.updateContext(sessionId, guidance);
    }

    /**
     * Get shared context for a specific agent.
     */
    public Map<String, Object> getSharedContextForAgent(String sessionId, Agent agent) {
        Map<String, Object> sharedContext = new HashMap<>();

        // Always include session context
        getSessionContext(sessionId).ifPresent(session -> sharedContext.put("session", session));

        // Include relevant specialized contexts based on agent type

        sharedContext.put(agent.getTechnicalDomain().toString(), agent.getOrCreateContext(sessionId));

        if (agent.getTechnicalDomain().equals(AgentType.ARCHITECTURE)) {
            ArchitectureAgent architectureAgent = (ArchitectureAgent) agent;
            // Architecture agent gets all contexts
            sharedContext.put("event-driven", architectureAgent.getOrCreateEventDrivenContext(sessionId));
            sharedContext.put("cicd", architectureAgent.getOrCreateCICDContext(sessionId));
            sharedContext.put("configuration", architectureAgent.getOrCreateConfigurationContext(sessionId));
            sharedContext.put("resilience", architectureAgent.getOrCreateResilienceContext(sessionId));
        }

        return sharedContext;
    }

    /**
     * Enhance guidance with context information.
     */
    public AgentResponse enhanceWithContext(AgentResponse response,
            AgentRequest request, Agent agent) {
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
        // Enhanced recommendations
        List<String> enhancedRecommendations = new ArrayList<>(response.getRecommendations());
        if (agent.getTechnicalDomain().equals(AgentType.EVENT_DRIVEN_ARCHITECTURE)) {
            EventDrivenContext eventCtx = (EventDrivenContext) agent.getOrCreateContext(sessionId);
            enhancedGuidance.append("Event-Driven Architecture Context:\n");
            enhancedGuidance.append("- Message Brokers: ").append(eventCtx.getMessageBrokers()).append("\n");
            enhancedGuidance.append("- Event Handlers: ").append(eventCtx.getEventHandlers()).append("\n");
            enhancedGuidance.append("- Event Schemas: ").append(eventCtx.getEventSchemas()).append("\n\n");

            enhancedRecommendations.add("Consider event schema versioning for " + eventCtx.getMessageBrokers());
            enhancedRecommendations.add("Ensure idempotent event handlers for reliability");
        }

        if (agent.getTechnicalDomain().equals(AgentType.EVENT_DRIVEN_ARCHITECTURE)) {
            CICDContext cicdCtx = (CICDContext) agent.getOrCreateContext(sessionId);
            enhancedGuidance.append("CI/CD Pipeline Context:\n");
            enhancedGuidance.append("- Build Tools: ").append(cicdCtx.getBuildTools()).append("\n");
            enhancedGuidance.append("- Deployment Strategies: ").append(cicdCtx.getDeploymentStrategies())
                    .append("\n\n");

            enhancedRecommendations.add("Integrate security scanning in CI/CD pipeline");
            enhancedRecommendations.add("Consider deployment strategies: " + cicdCtx.getDeploymentStrategies());
        }

        return AgentResponse.success(
                enhancedGuidance.toString(),
                response.getConfidence(), enhancedRecommendations);
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
    public void archiveSessionContext(String sessionId, Agent agent) {
        sessionContexts.remove(sessionId);
        agent.removeContext(sessionId);
    }

}
