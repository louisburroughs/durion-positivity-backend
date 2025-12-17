package com.positivity.agent.collaboration;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.registry.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Manages priority-based routing and advanced conflict resolution
 * Implements REQ-007.2, REQ-008.3, REQ-011.5
 */
@Component
public class PriorityBasedRoutingManager {

    private static final Logger logger = LoggerFactory.getLogger(PriorityBasedRoutingManager.class);

    private final AgentRegistry agentRegistry;
    private final AgentDependencyManager dependencyManager;
    private final MultiAgentConsistencyValidator consistencyValidator;

    // Priority weights for different routing criteria
    private static final double DOMAIN_MATCH_WEIGHT = 0.4;
    private static final double CAPABILITY_MATCH_WEIGHT = 0.3;
    private static final double PERFORMANCE_WEIGHT = 0.2;
    private static final double AVAILABILITY_WEIGHT = 0.1;

    public PriorityBasedRoutingManager(AgentRegistry agentRegistry,
            AgentDependencyManager dependencyManager,
            MultiAgentConsistencyValidator consistencyValidator) {
        this.agentRegistry = agentRegistry;
        this.dependencyManager = dependencyManager;
        this.consistencyValidator = consistencyValidator;
    }

    /**
     * Route request to agents based on priority and dependencies
     */
    public CompletableFuture<PriorityRoutingResult> routeWithPriority(AgentConsultationRequest request) {
        Instant start = Instant.now();

        // Get candidate agents
        List<Agent> candidateAgents = findCandidateAgents(request);

        if (candidateAgents.isEmpty()) {
            return CompletableFuture.completedFuture(
                    PriorityRoutingResult.noAgentsAvailable(request.requestId(),
                            Duration.between(start, Instant.now())));
        }

        // Calculate priority scores for each agent
        Map<String, Double> priorityScores = calculatePriorityScores(candidateAgents, request);

        // Sort agents by priority score (highest first)
        List<String> prioritizedAgents = priorityScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Apply dependency ordering
        List<String> dependencyOrderedAgents = dependencyManager.getAgentsInDependencyOrder(
                request, prioritizedAgents);

        logger.debug("Priority-ordered agents for request {}: {}",
                request.requestId(), dependencyOrderedAgents);

        // Execute consultations with priority-based routing
        return executeWithPriorityRouting(request, dependencyOrderedAgents, priorityScores, start);
    }

    /**
     * Resolve conflicts using advanced conflict resolution strategies
     */
    public ConflictResolutionResult resolveAdvancedConflicts(List<AgentGuidanceResponse> responses,
            AgentConsultationRequest request) {
        Instant start = Instant.now();

        if (responses.size() < 2) {
            return ConflictResolutionResult.noConflicts(responses.get(0), Duration.between(start, Instant.now()));
        }

        // Perform enhanced consistency validation
        MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult consistencyResult = consistencyValidator
                .validateMultiDimensionalConsistency(responses);

        if (consistencyResult.isConsistent()) {
            // No conflicts - merge responses
            AgentGuidanceResponse mergedResponse = mergeConsistentResponses(responses, request);
            return ConflictResolutionResult.resolved(mergedResponse, consistencyResult,
                    "Responses are consistent - merged successfully", Duration.between(start, Instant.now()));
        }

        // Apply conflict resolution strategies
        ConflictResolutionStrategy strategy = selectResolutionStrategy(consistencyResult, request);
        AgentGuidanceResponse resolvedResponse = applyResolutionStrategy(strategy, responses, request);

        Duration resolutionTime = Duration.between(start, Instant.now());

        return ConflictResolutionResult.resolved(resolvedResponse, consistencyResult,
                "Conflicts resolved using " + strategy.name(), resolutionTime);
    }

    private List<Agent> findCandidateAgents(AgentConsultationRequest request) {
        List<Agent> candidates = new ArrayList<>();

        // First, try domain-specific agents
        candidates.addAll(agentRegistry.getAgentsForDomain(request.domain()));

        // Add agents with relevant capabilities
        Set<String> requestCapabilities = extractCapabilitiesFromRequest(request);
        if (!requestCapabilities.isEmpty()) {
            candidates.addAll(agentRegistry.getAgentsWithCapabilities(requestCapabilities));
        }

        // Add cross-domain agents if needed
        Set<String> crossDomainAgentIds = dependencyManager.getCrossDomainAgents(request.domain());
        for (String agentId : crossDomainAgentIds) {
            agentRegistry.getAgent(agentId).ifPresent(candidates::add);
        }

        // NEW: Enhanced discovery for specialized agent types (REQ-012.1, REQ-013.1,
        // REQ-014.1, REQ-015.1)
        String query = request.query().toLowerCase();

        // Event-driven architecture queries
        if (query.contains("event") || query.contains("kafka") || query.contains("sns") ||
                query.contains("sqs") || query.contains("rabbitmq") || query.contains("message")) {
            candidates.addAll(findAgentsByType("event-driven"));
        }

        // CI/CD pipeline queries
        if (query.contains("cicd") || query.contains("ci/cd") || query.contains("pipeline") ||
                query.contains("build") || query.contains("deploy") || query.contains("jenkins")) {
            candidates.addAll(findAgentsByType("cicd"));
        }

        // Configuration management queries
        if (query.contains("config") || query.contains("configuration") || query.contains("feature flag") ||
                query.contains("secrets") || query.contains("vault") || query.contains("consul")) {
            candidates.addAll(findAgentsByType("configuration"));
        }

        // Resilience engineering queries
        if (query.contains("resilience") || query.contains("circuit breaker") || query.contains("retry") ||
                query.contains("chaos") || query.contains("bulkhead") || query.contains("failure")) {
            candidates.addAll(findAgentsByType("resilience"));
        }

        // Filter for available agents only
        return candidates.stream()
                .filter(Agent::isAvailable)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Find agents by specialized type using enhanced registry mappings (REQ-012.1,
     * REQ-013.1, REQ-014.1, REQ-015.1)
     */
    private List<Agent> findAgentsByType(String agentType) {
        List<Agent> typeAgents = new ArrayList<>();

        // Get all agents and filter by type-specific capabilities
        List<Agent> allAgents = agentRegistry.getAllAgents();

        for (Agent agent : allAgents) {
            boolean matchesType = false;

            switch (agentType) {
                case "event-driven":
                    matchesType = agent.getCapabilities().stream().anyMatch(
                            cap -> cap.contains("event") || cap.contains("kafka") || cap.contains("sns-sqs") ||
                                    cap.contains("rabbitmq") || cap.contains("message-brokers"));
                    break;

                case "cicd":
                    matchesType = agent.getCapabilities().stream()
                            .anyMatch(cap -> cap.contains("build-automation") || cap.contains("deployment-strategies")
                                    ||
                                    cap.contains("security-scanning") || cap.contains("pipeline-orchestration"));
                    break;

                case "configuration":
                    matchesType = agent.getCapabilities().stream()
                            .anyMatch(cap -> cap.contains("spring-cloud-config") || cap.contains("feature-flags") ||
                                    cap.contains("secrets-management") || cap.contains("configuration-validation"));
                    break;

                case "resilience":
                    matchesType = agent.getCapabilities().stream()
                            .anyMatch(cap -> cap.contains("circuit-breakers") || cap.contains("retry-patterns") ||
                                    cap.contains("bulkhead-patterns") || cap.contains("chaos-engineering"));
                    break;
            }

            if (matchesType) {
                typeAgents.add(agent);
            }
        }

        return typeAgents;
    }

    private Map<String, Double> calculatePriorityScores(List<Agent> agents, AgentConsultationRequest request) {
        Map<String, Double> scores = new HashMap<>();

        for (Agent agent : agents) {
            double score = 0.0;

            // Domain match score
            double domainScore = calculateDomainMatchScore(agent, request);
            score += domainScore * DOMAIN_MATCH_WEIGHT;

            // Capability match score
            double capabilityScore = calculateCapabilityMatchScore(agent, request);
            score += capabilityScore * CAPABILITY_MATCH_WEIGHT;

            // Performance score
            double performanceScore = calculatePerformanceScore(agent);
            score += performanceScore * PERFORMANCE_WEIGHT;

            // Availability score
            double availabilityScore = calculateAvailabilityScore(agent);
            score += availabilityScore * AVAILABILITY_WEIGHT;

            // Apply dependency manager priority
            int dependencyPriority = dependencyManager.getAgentPriority(agent.getId());
            score += (dependencyPriority / 100.0) * 0.1; // Normalize and apply small weight

            scores.put(agent.getId(), score);
        }

        return scores;
    }

    private double calculateDomainMatchScore(Agent agent, AgentConsultationRequest request) {
        if (agent.getDomain().equals(request.domain())) {
            return 1.0;
        }

        // Check for related domains
        Set<String> crossDomainAgents = dependencyManager.getCrossDomainAgents(request.domain());
        if (crossDomainAgents.contains(agent.getId())) {
            return 0.7;
        }

        return 0.0;
    }

    private double calculateCapabilityMatchScore(Agent agent, AgentConsultationRequest request) {
        Set<String> requestCapabilities = extractCapabilitiesFromRequest(request);
        if (requestCapabilities.isEmpty()) {
            return 0.5; // Neutral score if no specific capabilities requested
        }

        Set<String> agentCapabilities = new HashSet<>(agent.getCapabilities());
        agentCapabilities.retainAll(requestCapabilities);

        return requestCapabilities.isEmpty() ? 0.0 : (double) agentCapabilities.size() / requestCapabilities.size();
    }

    private double calculatePerformanceScore(Agent agent) {
        // Higher score for better performance (lower response time, higher accuracy)
        double responseTimeScore = Math.max(0, 1.0 - (agent.getMetrics().averageResponseTime().toMillis() / 5000.0));
        double accuracyScore = agent.getMetrics().currentAccuracy();

        return (responseTimeScore + accuracyScore) / 2.0;
    }

    private double calculateAvailabilityScore(Agent agent) {
        // Higher score for less loaded agents
        int activeRequests = agent.getMetrics().activeRequests();
        return Math.max(0, 1.0 - (activeRequests / 10.0)); // Assume 10 is max reasonable load
    }

    private Set<String> extractCapabilitiesFromRequest(AgentConsultationRequest request) {
        Set<String> capabilities = new HashSet<>();

        String query = request.query().toLowerCase();

        // Original agent capabilities
        if (query.contains("spring boot"))
            capabilities.add("spring-boot");
        if (query.contains("security"))
            capabilities.add("security");
        if (query.contains("test"))
            capabilities.add("testing");
        if (query.contains("deploy"))
            capabilities.add("deployment");
        if (query.contains("document"))
            capabilities.add("documentation");
        if (query.contains("architecture"))
            capabilities.add("architecture");

        // NEW: Event-Driven Architecture Agent capabilities (REQ-012)
        if (query.contains("event") || query.contains("kafka") || query.contains("sns") ||
                query.contains("sqs") || query.contains("rabbitmq") || query.contains("messaging"))
            capabilities.add("event-driven");
        if (query.contains("event schema") || query.contains("schema"))
            capabilities.add("event-schemas");
        if (query.contains("idempotent") || query.contains("event handler"))
            capabilities.add("idempotency");
        if (query.contains("event sourcing") || query.contains("cqrs"))
            capabilities.add("event-sourcing");

        // NEW: CI/CD Pipeline Agent capabilities (REQ-013)
        if (query.contains("cicd") || query.contains("ci/cd") || query.contains("pipeline") ||
                query.contains("build") || query.contains("jenkins") || query.contains("github actions"))
            capabilities.add("cicd");
        if (query.contains("maven") || query.contains("gradle") || query.contains("build automation"))
            capabilities.add("build-automation");
        if (query.contains("sast") || query.contains("dast") || query.contains("security scanning"))
            capabilities.add("security-scanning");
        if (query.contains("blue-green") || query.contains("canary") || query.contains("deployment strategy"))
            capabilities.add("deployment-strategies");

        // NEW: Configuration Management Agent capabilities (REQ-014)
        if (query.contains("config") || query.contains("configuration") || query.contains("spring cloud config"))
            capabilities.add("configuration");
        if (query.contains("consul") || query.contains("etcd"))
            capabilities.add("centralized-config");
        if (query.contains("feature flag") || query.contains("toggle"))
            capabilities.add("feature-flags");
        if (query.contains("vault") || query.contains("secrets") || query.contains("aws secrets"))
            capabilities.add("secrets-management");

        // NEW: Resilience Engineering Agent capabilities (REQ-015)
        if (query.contains("resilience") || query.contains("circuit breaker") || query.contains("retry"))
            capabilities.add("resilience");
        if (query.contains("resilience4j") || query.contains("hystrix"))
            capabilities.add("circuit-breakers");
        if (query.contains("bulkhead") || query.contains("rate limit"))
            capabilities.add("bulkhead-pattern");
        if (query.contains("chaos") || query.contains("failure injection"))
            capabilities.add("chaos-engineering");

        return capabilities;
    }

    private CompletableFuture<PriorityRoutingResult> executeWithPriorityRouting(
            AgentConsultationRequest request, List<String> prioritizedAgents,
            Map<String, Double> priorityScores, Instant start) {

        // Execute consultations in priority order with parallel execution for
        // same-priority agents
        List<CompletableFuture<AgentGuidanceResponse>> futures = new ArrayList<>();

        for (String agentId : prioritizedAgents) {
            Optional<Agent> agentOpt = agentRegistry.getAgent(agentId);
            if (agentOpt.isPresent()) {
                Agent agent = agentOpt.get();
                futures.add(agent.provideGuidance(request));
            }
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<AgentGuidanceResponse> responses = futures.stream()
                            .map(CompletableFuture::join)
                            .filter(AgentGuidanceResponse::isSuccessful)
                            .collect(Collectors.toList());

                    Duration routingTime = Duration.between(start, Instant.now());

                    if (responses.isEmpty()) {
                        return PriorityRoutingResult.allAgentsFailed(request.requestId(), routingTime);
                    }

                    return PriorityRoutingResult.success(request.requestId(), responses,
                            prioritizedAgents, priorityScores, routingTime);
                });
    }

    private ConflictResolutionStrategy selectResolutionStrategy(
            MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult consistencyResult,
            AgentConsultationRequest request) {

        // Select strategy based on the type of conflicts detected
        if (consistencyResult.hasArchitecturalConflicts()) {
            return ConflictResolutionStrategy.ARCHITECTURAL_PRIORITY;
        }

        if (consistencyResult.hasCrossDomainConflicts()) {
            return ConflictResolutionStrategy.DOMAIN_EXPERTISE;
        }

        if (consistencyResult.getConfidenceConsistency().getScore() < 0.5) {
            return ConflictResolutionStrategy.HIGHEST_CONFIDENCE;
        }

        return ConflictResolutionStrategy.WEIGHTED_MERGE;
    }

    private AgentGuidanceResponse applyResolutionStrategy(ConflictResolutionStrategy strategy,
            List<AgentGuidanceResponse> responses,
            AgentConsultationRequest request) {

        switch (strategy) {
            case ARCHITECTURAL_PRIORITY:
                return resolveByArchitecturalPriority(responses);

            case DOMAIN_EXPERTISE:
                return resolveByDomainExpertise(responses, request);

            case HIGHEST_CONFIDENCE:
                return resolveByHighestConfidence(responses);

            case WEIGHTED_MERGE:
                return resolveByWeightedMerge(responses, request);

            default:
                return responses.get(0); // Fallback
        }
    }

    private AgentGuidanceResponse resolveByArchitecturalPriority(List<AgentGuidanceResponse> responses) {
        // Prioritize architecture-related agents
        return responses.stream()
                .filter(r -> r.agentId().contains("architecture") || r.agentId().contains("governance"))
                .max(Comparator.comparing(AgentGuidanceResponse::confidence))
                .orElse(responses.stream()
                        .max(Comparator.comparing(AgentGuidanceResponse::confidence))
                        .orElse(responses.get(0)));
    }

    private AgentGuidanceResponse resolveByDomainExpertise(List<AgentGuidanceResponse> responses,
            AgentConsultationRequest request) {
        // Enhanced domain expertise resolution for new agent types
        String query = request.query().toLowerCase();

        // Prioritize agents that match the request domain first
        Optional<AgentGuidanceResponse> domainMatch = responses.stream()
                .filter(r -> r.agentId().contains(request.domain()))
                .max(Comparator.comparing(AgentGuidanceResponse::confidence));

        if (domainMatch.isPresent()) {
            return domainMatch.get();
        }

        // NEW: Enhanced domain-specific prioritization for new agent types

        // Event-driven queries - prioritize EventDrivenArchitectureAgent
        if (query.contains("event") || query.contains("kafka") || query.contains("messaging") ||
                query.contains("sns") || query.contains("sqs") || query.contains("rabbitmq")) {
            Optional<AgentGuidanceResponse> eventAgent = responses.stream()
                    .filter(r -> r.agentId().contains("event-driven"))
                    .max(Comparator.comparing(AgentGuidanceResponse::confidence));
            if (eventAgent.isPresent())
                return eventAgent.get();
        }

        // CI/CD queries - prioritize CICDPipelineAgent
        if (query.contains("cicd") || query.contains("pipeline") || query.contains("build") ||
                query.contains("jenkins") || query.contains("github actions")
                || query.contains("deployment strategy")) {
            Optional<AgentGuidanceResponse> cicdAgent = responses.stream()
                    .filter(r -> r.agentId().contains("cicd"))
                    .max(Comparator.comparing(AgentGuidanceResponse::confidence));
            if (cicdAgent.isPresent())
                return cicdAgent.get();
        }

        // Configuration queries - prioritize ConfigurationManagementAgent
        if (query.contains("config") || query.contains("feature flag") || query.contains("secrets") ||
                query.contains("vault") || query.contains("consul") || query.contains("spring cloud config")) {
            Optional<AgentGuidanceResponse> configAgent = responses.stream()
                    .filter(r -> r.agentId().contains("configuration"))
                    .max(Comparator.comparing(AgentGuidanceResponse::confidence));
            if (configAgent.isPresent())
                return configAgent.get();
        }

        // Resilience queries - prioritize ResilienceEngineeringAgent
        if (query.contains("resilience") || query.contains("circuit breaker") || query.contains("retry") ||
                query.contains("chaos") || query.contains("bulkhead") || query.contains("resilience4j")) {
            Optional<AgentGuidanceResponse> resilienceAgent = responses.stream()
                    .filter(r -> r.agentId().contains("resilience"))
                    .max(Comparator.comparing(AgentGuidanceResponse::confidence));
            if (resilienceAgent.isPresent())
                return resilienceAgent.get();
        }

        // Fallback to highest confidence
        return resolveByHighestConfidence(responses);
    }

    private AgentGuidanceResponse resolveByHighestConfidence(List<AgentGuidanceResponse> responses) {
        return responses.stream()
                .max(Comparator.comparing(AgentGuidanceResponse::confidence))
                .orElse(responses.get(0));
    }

    private AgentGuidanceResponse resolveByWeightedMerge(List<AgentGuidanceResponse> responses,
            AgentConsultationRequest request) {
        // Create a weighted merge of all responses
        double totalConfidence = responses.stream().mapToDouble(AgentGuidanceResponse::confidence).sum();

        StringBuilder mergedGuidance = new StringBuilder();
        mergedGuidance.append("## Merged Guidance (Weighted by Confidence)\n\n");

        List<String> mergedRecommendations = new ArrayList<>();

        for (AgentGuidanceResponse response : responses) {
            double weight = response.confidence() / totalConfidence;
            mergedGuidance.append(String.format("### %s (Weight: %.2f)\n%s\n\n",
                    response.agentId(), weight, response.guidance()));
            mergedRecommendations.addAll(response.recommendations());
        }

        double averageConfidence = responses.stream()
                .mapToDouble(AgentGuidanceResponse::confidence)
                .average()
                .orElse(0.0);

        return AgentGuidanceResponse.success(
                request.requestId(),
                "merged-response",
                mergedGuidance.toString(),
                averageConfidence,
                mergedRecommendations.stream().distinct().collect(Collectors.toList()),
                Duration.ZERO);
    }

    private AgentGuidanceResponse mergeConsistentResponses(List<AgentGuidanceResponse> responses,
            AgentConsultationRequest request) {
        StringBuilder mergedGuidance = new StringBuilder();
        mergedGuidance.append("## Consistent Multi-Agent Guidance\n\n");

        Set<String> allRecommendations = new HashSet<>();

        for (AgentGuidanceResponse response : responses) {
            mergedGuidance.append(String.format("**%s**: %s\n\n", response.agentId(), response.guidance()));
            allRecommendations.addAll(response.recommendations());
        }

        double averageConfidence = responses.stream()
                .mapToDouble(AgentGuidanceResponse::confidence)
                .average()
                .orElse(0.0);

        return AgentGuidanceResponse.success(
                request.requestId(),
                "consistent-merge",
                mergedGuidance.toString(),
                averageConfidence,
                new ArrayList<>(allRecommendations),
                Duration.ZERO);
    }

    /**
     * Conflict resolution strategies
     */
    public enum ConflictResolutionStrategy {
        ARCHITECTURAL_PRIORITY, // Prioritize architectural agents
        DOMAIN_EXPERTISE, // Prioritize domain-specific agents
        HIGHEST_CONFIDENCE, // Choose response with highest confidence
        WEIGHTED_MERGE // Merge responses weighted by confidence
    }

    /**
     * Result of priority-based routing
     */
    public static class PriorityRoutingResult {
        private final String requestId;
        private final List<AgentGuidanceResponse> responses;
        private final List<String> prioritizedAgents;
        private final Map<String, Double> priorityScores;
        private final Duration routingTime;
        private final RoutingStatus status;

        public enum RoutingStatus {
            SUCCESS, NO_AGENTS_AVAILABLE, ALL_AGENTS_FAILED
        }

        private PriorityRoutingResult(String requestId, List<AgentGuidanceResponse> responses,
                List<String> prioritizedAgents, Map<String, Double> priorityScores,
                Duration routingTime, RoutingStatus status) {
            this.requestId = requestId;
            this.responses = responses;
            this.prioritizedAgents = prioritizedAgents;
            this.priorityScores = priorityScores;
            this.routingTime = routingTime;
            this.status = status;
        }

        public static PriorityRoutingResult success(String requestId, List<AgentGuidanceResponse> responses,
                List<String> prioritizedAgents, Map<String, Double> priorityScores,
                Duration routingTime) {
            return new PriorityRoutingResult(requestId, responses, prioritizedAgents, priorityScores,
                    routingTime, RoutingStatus.SUCCESS);
        }

        public static PriorityRoutingResult noAgentsAvailable(String requestId, Duration routingTime) {
            return new PriorityRoutingResult(requestId, List.of(), List.of(), Map.of(),
                    routingTime, RoutingStatus.NO_AGENTS_AVAILABLE);
        }

        public static PriorityRoutingResult allAgentsFailed(String requestId, Duration routingTime) {
            return new PriorityRoutingResult(requestId, List.of(), List.of(), Map.of(),
                    routingTime, RoutingStatus.ALL_AGENTS_FAILED);
        }

        // Getters
        public String getRequestId() {
            return requestId;
        }

        public List<AgentGuidanceResponse> getResponses() {
            return responses;
        }

        public List<String> getPrioritizedAgents() {
            return prioritizedAgents;
        }

        public Map<String, Double> getPriorityScores() {
            return priorityScores;
        }

        public Duration getRoutingTime() {
            return routingTime;
        }

        public RoutingStatus getStatus() {
            return status;
        }

        public boolean isSuccessful() {
            return status == RoutingStatus.SUCCESS;
        }

        /**
         * Get the primary (first/highest priority) response
         */
        public AgentGuidanceResponse getPrimaryResponse() {
            if (responses.isEmpty()) {
                throw new IllegalStateException("No responses available");
            }
            return responses.get(0);
        }
    }

    /**
     * Result of conflict resolution
     */
    public static class ConflictResolutionResult {
        private final AgentGuidanceResponse resolvedResponse;
        private final MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult consistencyResult;
        private final String resolutionMethod;
        private final Duration resolutionTime;
        private final boolean hadConflicts;

        private ConflictResolutionResult(AgentGuidanceResponse resolvedResponse,
                MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult consistencyResult,
                String resolutionMethod, Duration resolutionTime, boolean hadConflicts) {
            this.resolvedResponse = resolvedResponse;
            this.consistencyResult = consistencyResult;
            this.resolutionMethod = resolutionMethod;
            this.resolutionTime = resolutionTime;
            this.hadConflicts = hadConflicts;
        }

        public static ConflictResolutionResult resolved(AgentGuidanceResponse response,
                MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult consistencyResult,
                String method, Duration time) {
            return new ConflictResolutionResult(response, consistencyResult, method, time, true);
        }

        public static ConflictResolutionResult noConflicts(AgentGuidanceResponse response, Duration time) {
            return new ConflictResolutionResult(response, null, "No conflicts detected", time, false);
        }

        // Getters
        public AgentGuidanceResponse getResolvedResponse() {
            return resolvedResponse;
        }

        public MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult getConsistencyResult() {
            return consistencyResult;
        }

        public String getResolutionMethod() {
            return resolutionMethod;
        }

        public Duration getResolutionTime() {
            return resolutionTime;
        }

        public boolean hadConflicts() {
            return hadConflicts;
        }
    }

}