package com.pos.agent.collaboration;

import com.pos.agent.AgentConsultationRequest;
import com.pos.agent.AgentGuidanceResponse;
import com.pos.agent.context.*;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Context-aware guidance manager that maintains session context
 * and specialized domain contexts across agent consultations.
 * 
 * Requirements: REQ-001.3, REQ-012.1, REQ-013.1, REQ-014.1, REQ-015.1
 */
public class ContextAwareGuidanceManager {
    private static final Duration SESSION_TIMEOUT = Duration.ofMinutes(30);
    private static final Set<String> REQUIRED_CONTEXT_KEYS = Set.of(
            "session-id", "project-context", "architectural-decisions",
            "current-task", "domain-constraints", "event-driven-context",
            "cicd-context", "configuration-context", "resilience-context");

    private final Map<String, SessionContext> sessionContexts = new ConcurrentHashMap<>();
    private final Map<String, EventDrivenContext> eventDrivenContexts = new ConcurrentHashMap<>();
    private final Map<String, CICDContext> cicdContexts = new ConcurrentHashMap<>();
    private final Map<String, ConfigurationContext> configurationContexts = new ConcurrentHashMap<>();
    private final Map<String, ResilienceContext> resilienceContexts = new ConcurrentHashMap<>();

    /**
     * Session context for tracking progress and decisions.
     */
    public static class SessionContext {
        private final String sessionId;
        private final Instant createdAt;
        private Instant lastUpdated;
        private String taskObjective;
        private Map<String, Object> architecturalDecisions;
        private List<String> nextSteps;

        public SessionContext(String sessionId) {
            this.sessionId = sessionId;
            this.createdAt = Instant.now();
            this.lastUpdated = Instant.now();
            this.architecturalDecisions = new HashMap<>();
            this.nextSteps = new ArrayList<>();
        }

        public String getSessionId() {
            return sessionId;
        }

        public Instant getCreatedAt() {
            return createdAt;
        }

        public Instant getLastUpdated() {
            return lastUpdated;
        }

        public void setLastUpdated(Instant lastUpdated) {
            this.lastUpdated = lastUpdated;
        }

        public String getTaskObjective() {
            return taskObjective;
        }

        public void setTaskObjective(String taskObjective) {
            this.taskObjective = taskObjective;
            this.lastUpdated = Instant.now();
        }

        public Map<String, Object> getArchitecturalDecisions() {
            return new HashMap<>(architecturalDecisions);
        }

        public void setArchitecturalDecisions(Map<String, Object> decisions) {
            this.architecturalDecisions = new HashMap<>(decisions);
            this.lastUpdated = Instant.now();
        }

        public List<String> getNextSteps() {
            return new ArrayList<>(nextSteps);
        }

        public void setNextSteps(List<String> steps) {
            this.nextSteps = new ArrayList<>(steps);
            this.lastUpdated = Instant.now();
        }

        public boolean isStale() {
            return Duration.between(lastUpdated, Instant.now()).compareTo(SESSION_TIMEOUT) > 0;
        }
    }

    /**
     * Context validation result.
     */
    public static class ContextValidationResult {
        private final boolean sufficient;
        private final List<String> missingInputs;
        private final List<String> missingDecisions;
        private final Duration validationTime;

        public ContextValidationResult(boolean sufficient, List<String> missingInputs,
                List<String> missingDecisions, Duration validationTime) {
            this.sufficient = sufficient;
            this.missingInputs = missingInputs;
            this.missingDecisions = missingDecisions;
            this.validationTime = validationTime;
        }

        public boolean isSufficient() {
            return sufficient;
        }

        public List<String> getMissingInputs() {
            return new ArrayList<>(missingInputs);
        }

        public List<String> getMissingDecisions() {
            return new ArrayList<>(missingDecisions);
        }

        public Duration getValidationTime() {
            return validationTime;
        }

        public String getInsufficientContextMessage() {
            if (sufficient) {
                return "";
            }
            StringBuilder sb = new StringBuilder("Context insufficient – re-anchor needed.\n");
            if (!missingInputs.isEmpty()) {
                sb.append("Missing inputs: ").append(String.join(", ", missingInputs)).append("\n");
            }
            if (!missingDecisions.isEmpty()) {
                sb.append("Missing decisions: ").append(String.join(", ", missingDecisions));
            }
            return sb.toString();
        }
    }

    /**
     * Validates if request context is sufficient.
     */
    public ContextValidationResult validateContext(AgentConsultationRequest request) {
        Instant start = Instant.now();
        List<String> missing = new ArrayList<>();
        List<String> missingDecisions = new ArrayList<>();

        Map<String, Object> context = request.context();

        // Check required keys
        for (String key : REQUIRED_CONTEXT_KEYS) {
            if (!context.containsKey(key)) {
                missing.add(key);
            }
        }

        // Check session staleness
        if (context.containsKey("session-id")) {
            String sessionId = context.get("session-id").toString();
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
                sid -> new EventDrivenContext("event-" + UUID.randomUUID(), sid));
    }

    /**
     * Get or create CICDContext for a session.
     */
    public CICDContext getOrCreateCICDContext(String sessionId) {
        return cicdContexts.computeIfAbsent(sessionId,
                sid -> new CICDContext("cicd-" + UUID.randomUUID(), sid));
    }

    /**
     * Get or create ConfigurationContext for a session.
     */
    public ConfigurationContext getOrCreateConfigurationContext(String sessionId) {
        return configurationContexts.computeIfAbsent(sessionId,
                sid -> new ConfigurationContext("config-" + UUID.randomUUID(), sid));
    }

    /**
     * Get or create ResilienceContext for a session.
     */
    public ResilienceContext getOrCreateResilienceContext(String sessionId) {
        return resilienceContexts.computeIfAbsent(sessionId,
                sid -> new ResilienceContext("resilience-" + UUID.randomUUID(), sid));
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
    public void updateSpecializedContext(String sessionId, String agentId, AgentGuidanceResponse response) {
        String guidance = response.guidance().toLowerCase();

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
    public AgentGuidanceResponse enhanceWithContext(AgentGuidanceResponse response,
            AgentConsultationRequest request) {
        String sessionId = request.context().get("session-id").toString();

        StringBuilder enhancedGuidance = new StringBuilder();
        enhancedGuidance.append("=== Context-Aware Guidance ===\n\n");
        enhancedGuidance.append("Original Guidance:\n");
        enhancedGuidance.append(response.guidance()).append("\n\n");

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
        List<String> enhancedRecommendations = new ArrayList<>(response.recommendations());
        if (eventCtx != null && !eventCtx.getMessageBrokers().isEmpty()) {
            enhancedRecommendations.add("Consider event schema versioning for " + eventCtx.getMessageBrokers());
            enhancedRecommendations.add("Ensure idempotent event handlers for reliability");
        }
        if (cicdCtx != null && !cicdCtx.getBuildTools().isEmpty()) {
            enhancedRecommendations.add("Integrate security scanning in CI/CD pipeline");
            enhancedRecommendations.add("Consider deployment strategies: " + cicdCtx.getDeploymentStrategies());
        }

        return AgentGuidanceResponse.success(
                response.requestId(),
                response.agentId(),
                enhancedGuidance.toString(),
                response.confidence(),
                enhancedRecommendations,
                response.processingTime());
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
