package com.positivity.agent.collaboration;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.registry.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Comprehensive orchestrator for agent collaboration system
 * Integrates all collaboration components: dependencies, context, consistency,
 * and priority routing
 * Implements REQ-001.3, REQ-005.1, REQ-007.2, REQ-008.3, REQ-011.5
 */
@Component
public class ComprehensiveCollaborationOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(ComprehensiveCollaborationOrchestrator.class);

    private final AgentRegistry agentRegistry;
    private final AgentDependencyManager dependencyManager;
    private final ContextAwareGuidanceManager contextManager;
    private final MultiAgentConsistencyValidator consistencyValidator;
    private final PriorityBasedRoutingManager routingManager;
    private final DefaultCollaborationProtocol collaborationProtocol;

    public ComprehensiveCollaborationOrchestrator(
            AgentRegistry agentRegistry,
            AgentDependencyManager dependencyManager,
            ContextAwareGuidanceManager contextManager,
            MultiAgentConsistencyValidator consistencyValidator,
            PriorityBasedRoutingManager routingManager,
            DefaultCollaborationProtocol collaborationProtocol) {

        this.agentRegistry = agentRegistry;
        this.dependencyManager = dependencyManager;
        this.contextManager = contextManager;
        this.consistencyValidator = consistencyValidator;
        this.routingManager = routingManager;
        this.collaborationProtocol = collaborationProtocol;
    }

    /**
     * Execute comprehensive agent collaboration with all advanced features
     */
    public CompletableFuture<ComprehensiveCollaborationResult> executeComprehensiveCollaboration(
            AgentConsultationRequest request) {

        Instant start = Instant.now();
        logger.info("Starting comprehensive collaboration for request: {}", request.requestId());

        return validateContextAndProceed(request, start)
                .thenCompose(this::executeWithPriorityRouting)
                .thenCompose(this::validateConsistencyAndResolveConflicts)
                .thenApply(result -> enhanceWithContextualGuidance(result, request, start))
                .exceptionally(throwable -> {
                    logger.error("Comprehensive collaboration failed for request {}",
                            request.requestId(), throwable);
                    return ComprehensiveCollaborationResult.failure(
                            request.requestId(),
                            "Collaboration failed: " + throwable.getMessage(),
                            Duration.between(start, Instant.now()));
                });
    }

    /**
     * Validate context integrity before proceeding with collaboration
     */
    private CompletableFuture<ContextValidatedRequest> validateContextAndProceed(
            AgentConsultationRequest request, Instant start) {

        ContextAwareGuidanceManager.ContextValidationResult contextValidation = contextManager.validateContext(request);

        if (!contextValidation.isSufficient()) {
            logger.warn("Context insufficient for request {}: {}",
                    request.requestId(), contextValidation.getInsufficientContextMessage());

            return CompletableFuture.completedFuture(
                    ContextValidatedRequest.insufficient(request, contextValidation));
        }

        logger.debug("Context validation passed for request {} in {}",
                request.requestId(), contextValidation.getValidationTime());

        return CompletableFuture.completedFuture(
                ContextValidatedRequest.sufficient(request, contextValidation));
    }

    /**
     * Execute priority-based routing with dependency management
     */
    private CompletableFuture<RoutingExecutedResult> executeWithPriorityRouting(
            ContextValidatedRequest contextValidatedRequest) {

        if (!contextValidatedRequest.isContextSufficient()) {
            return CompletableFuture.completedFuture(
                    RoutingExecutedResult.contextInsufficient(contextValidatedRequest));
        }

        AgentConsultationRequest request = contextValidatedRequest.getRequest();

        return routingManager.routeWithPriority(request)
                .thenApply(routingResult -> RoutingExecutedResult.success(contextValidatedRequest, routingResult));
    }

    /**
     * Validate consistency and resolve conflicts if needed
     */
    private CompletableFuture<ConsistencyValidatedResult> validateConsistencyAndResolveConflicts(
            RoutingExecutedResult routingResult) {

        if (!routingResult.isSuccessful()) {
            return CompletableFuture.completedFuture(
                    ConsistencyValidatedResult.routingFailed(routingResult));
        }

        List<AgentGuidanceResponse> responses = routingResult.getRoutingResult().getResponses();
        AgentConsultationRequest request = routingResult.getContextValidatedRequest().getRequest();

        if (responses.size() < 2) {
            // Single response - no consistency validation needed
            return CompletableFuture.completedFuture(
                    ConsistencyValidatedResult.singleResponse(routingResult, responses.get(0)));
        }

        // Validate consistency across multiple responses
        MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult consistencyResult = consistencyValidator
                .validateMultiDimensionalConsistency(responses);

        if (consistencyResult.isConsistent()) {
            // Responses are consistent - merge them
            AgentGuidanceResponse mergedResponse = mergeConsistentResponses(responses, request);
            return CompletableFuture.completedFuture(
                    ConsistencyValidatedResult.consistent(routingResult, mergedResponse, consistencyResult));
        }

        // Resolve conflicts using advanced conflict resolution
        PriorityBasedRoutingManager.ConflictResolutionResult conflictResolution = routingManager
                .resolveAdvancedConflicts(responses, request);
        return CompletableFuture.completedFuture(
                ConsistencyValidatedResult.conflictsResolved(routingResult, conflictResolution));
    }

    /**
     * Enhance final result with contextual guidance
     */
    private ComprehensiveCollaborationResult enhanceWithContextualGuidance(
            ConsistencyValidatedResult consistencyResult, AgentConsultationRequest request, Instant start) {

        if (!consistencyResult.isSuccessful()) {
            return ComprehensiveCollaborationResult.failure(
                    request.requestId(),
                    "Collaboration failed: " + consistencyResult.getFailureReason(),
                    Duration.between(start, Instant.now()));
        }

        // Enhance the final response with contextual information
        AgentGuidanceResponse finalResponse = consistencyResult.getFinalResponse();
        AgentGuidanceResponse enhancedResponse = contextManager.enhanceWithContext(finalResponse, request);

        Duration totalTime = Duration.between(start, Instant.now());

        // Check performance requirements (≤ 3 seconds for 99% of requests)
        if (totalTime.compareTo(Duration.ofSeconds(3)) > 0) {
            logger.warn("Comprehensive collaboration exceeded 3-second threshold: {} for request {}",
                    totalTime, request.requestId());
        }

        return ComprehensiveCollaborationResult.success(
                request.requestId(),
                enhancedResponse,
                consistencyResult.getRoutingResult().getRoutingResult(),
                consistencyResult.getConsistencyValidationResult(),
                consistencyResult.getConflictResolutionResult(),
                totalTime);
    }

    /**
     * Merge consistent responses into a single comprehensive response
     */
    private AgentGuidanceResponse mergeConsistentResponses(List<AgentGuidanceResponse> responses,
            AgentConsultationRequest request) {

        StringBuilder mergedGuidance = new StringBuilder();
        mergedGuidance.append("## Comprehensive Multi-Agent Guidance\n\n");
        mergedGuidance
                .append("*This guidance represents the consistent consensus of multiple specialized agents.*\n\n");

        // Group responses by agent type for better organization
        responses.forEach(response -> {
            mergedGuidance.append(String.format("### %s\n", response.agentId()));
            mergedGuidance.append(response.guidance()).append("\n\n");
        });

        // Merge recommendations and remove duplicates
        List<String> mergedRecommendations = responses.stream()
                .flatMap(r -> r.recommendations().stream())
                .distinct()
                .collect(Collectors.toList());

        // Calculate weighted average confidence
        double totalConfidence = responses.stream()
                .mapToDouble(AgentGuidanceResponse::confidence)
                .average()
                .orElse(0.0);

        return AgentGuidanceResponse.success(
                request.requestId(),
                "comprehensive-collaboration",
                mergedGuidance.toString(),
                totalConfidence,
                mergedRecommendations,
                Duration.ZERO);
    }

    /**
     * Get collaboration system health status
     */
    public CollaborationSystemHealth getSystemHealth() {
        return new CollaborationSystemHealth(
                agentRegistry.getHealthStatus(),
                dependencyManager.validateDependencyGraph(),
                contextManager.getSessionContext("health-check").isPresent());
    }

    // Helper classes for managing the collaboration pipeline

    private static class ContextValidatedRequest {
        private final AgentConsultationRequest request;
        private final ContextAwareGuidanceManager.ContextValidationResult validationResult;
        private final boolean isContextSufficient;

        private ContextValidatedRequest(AgentConsultationRequest request,
                ContextAwareGuidanceManager.ContextValidationResult validationResult,
                boolean isContextSufficient) {
            this.request = request;
            this.validationResult = validationResult;
            this.isContextSufficient = isContextSufficient;
        }

        public static ContextValidatedRequest sufficient(AgentConsultationRequest request,
                ContextAwareGuidanceManager.ContextValidationResult validationResult) {
            return new ContextValidatedRequest(request, validationResult, true);
        }

        public static ContextValidatedRequest insufficient(AgentConsultationRequest request,
                ContextAwareGuidanceManager.ContextValidationResult validationResult) {
            return new ContextValidatedRequest(request, validationResult, false);
        }

        public AgentConsultationRequest getRequest() {
            return request;
        }

        public ContextAwareGuidanceManager.ContextValidationResult getValidationResult() {
            return validationResult;
        }

        public boolean isContextSufficient() {
            return isContextSufficient;
        }
    }

    private static class RoutingExecutedResult {
        private final ContextValidatedRequest contextValidatedRequest;
        private final PriorityBasedRoutingManager.PriorityRoutingResult routingResult;
        private final boolean isSuccessful;

        private RoutingExecutedResult(ContextValidatedRequest contextValidatedRequest,
                PriorityBasedRoutingManager.PriorityRoutingResult routingResult,
                boolean isSuccessful) {
            this.contextValidatedRequest = contextValidatedRequest;
            this.routingResult = routingResult;
            this.isSuccessful = isSuccessful;
        }

        public static RoutingExecutedResult success(ContextValidatedRequest contextValidatedRequest,
                PriorityBasedRoutingManager.PriorityRoutingResult routingResult) {
            return new RoutingExecutedResult(contextValidatedRequest, routingResult,
                    routingResult.isSuccessful());
        }

        public static RoutingExecutedResult contextInsufficient(ContextValidatedRequest contextValidatedRequest) {
            return new RoutingExecutedResult(contextValidatedRequest, null, false);
        }

        public ContextValidatedRequest getContextValidatedRequest() {
            return contextValidatedRequest;
        }

        public PriorityBasedRoutingManager.PriorityRoutingResult getRoutingResult() {
            return routingResult;
        }

        public boolean isSuccessful() {
            return isSuccessful;
        }
    }

    private static class ConsistencyValidatedResult {
        private final RoutingExecutedResult routingResult;
        private final AgentGuidanceResponse finalResponse;
        private final MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult consistencyValidationResult;
        private final PriorityBasedRoutingManager.ConflictResolutionResult conflictResolutionResult;
        private final boolean isSuccessful;
        private final String failureReason;

        private ConsistencyValidatedResult(RoutingExecutedResult routingResult,
                AgentGuidanceResponse finalResponse,
                MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult consistencyValidationResult,
                PriorityBasedRoutingManager.ConflictResolutionResult conflictResolutionResult,
                boolean isSuccessful,
                String failureReason) {
            this.routingResult = routingResult;
            this.finalResponse = finalResponse;
            this.consistencyValidationResult = consistencyValidationResult;
            this.conflictResolutionResult = conflictResolutionResult;
            this.isSuccessful = isSuccessful;
            this.failureReason = failureReason;
        }

        public static ConsistencyValidatedResult singleResponse(RoutingExecutedResult routingResult,
                AgentGuidanceResponse response) {
            return new ConsistencyValidatedResult(routingResult, response, null, null, true, null);
        }

        public static ConsistencyValidatedResult consistent(RoutingExecutedResult routingResult,
                AgentGuidanceResponse mergedResponse,
                MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult consistencyResult) {
            return new ConsistencyValidatedResult(routingResult, mergedResponse, consistencyResult, null, true, null);
        }

        public static ConsistencyValidatedResult conflictsResolved(RoutingExecutedResult routingResult,
                PriorityBasedRoutingManager.ConflictResolutionResult conflictResolution) {
            return new ConsistencyValidatedResult(routingResult, conflictResolution.getResolvedResponse(),
                    conflictResolution.getConsistencyResult(), conflictResolution, true, null);
        }

        public static ConsistencyValidatedResult routingFailed(RoutingExecutedResult routingResult) {
            return new ConsistencyValidatedResult(routingResult, null, null, null, false, "Routing failed");
        }

        public RoutingExecutedResult getRoutingResult() {
            return routingResult;
        }

        public AgentGuidanceResponse getFinalResponse() {
            return finalResponse;
        }

        public MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult getConsistencyValidationResult() {
            return consistencyValidationResult;
        }

        public PriorityBasedRoutingManager.ConflictResolutionResult getConflictResolutionResult() {
            return conflictResolutionResult;
        }

        public boolean isSuccessful() {
            return isSuccessful;
        }

        public String getFailureReason() {
            return failureReason;
        }
    }

    /**
     * Comprehensive collaboration result
     */
    public static class ComprehensiveCollaborationResult {
        private final String requestId;
        private final AgentGuidanceResponse finalResponse;
        private final PriorityBasedRoutingManager.PriorityRoutingResult routingResult;
        private final MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult consistencyResult;
        private final PriorityBasedRoutingManager.ConflictResolutionResult conflictResolutionResult;
        private final Duration totalProcessingTime;
        private final boolean isSuccessful;
        private final String failureReason;

        private ComprehensiveCollaborationResult(String requestId, AgentGuidanceResponse finalResponse,
                PriorityBasedRoutingManager.PriorityRoutingResult routingResult,
                MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult consistencyResult,
                PriorityBasedRoutingManager.ConflictResolutionResult conflictResolutionResult,
                Duration totalProcessingTime, boolean isSuccessful, String failureReason) {
            this.requestId = requestId;
            this.finalResponse = finalResponse;
            this.routingResult = routingResult;
            this.consistencyResult = consistencyResult;
            this.conflictResolutionResult = conflictResolutionResult;
            this.totalProcessingTime = totalProcessingTime;
            this.isSuccessful = isSuccessful;
            this.failureReason = failureReason;
        }

        public static ComprehensiveCollaborationResult success(String requestId, AgentGuidanceResponse finalResponse,
                PriorityBasedRoutingManager.PriorityRoutingResult routingResult,
                MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult consistencyResult,
                PriorityBasedRoutingManager.ConflictResolutionResult conflictResolutionResult,
                Duration totalProcessingTime) {
            return new ComprehensiveCollaborationResult(requestId, finalResponse, routingResult, consistencyResult,
                    conflictResolutionResult, totalProcessingTime, true, null);
        }

        public static ComprehensiveCollaborationResult failure(String requestId, String failureReason,
                Duration totalProcessingTime) {
            return new ComprehensiveCollaborationResult(requestId, null, null, null, null,
                    totalProcessingTime, false, failureReason);
        }

        // Getters
        public String getRequestId() {
            return requestId;
        }

        public AgentGuidanceResponse getFinalResponse() {
            return finalResponse;
        }

        public PriorityBasedRoutingManager.PriorityRoutingResult getRoutingResult() {
            return routingResult;
        }

        public MultiAgentConsistencyValidator.EnhancedConsistencyValidationResult getConsistencyResult() {
            return consistencyResult;
        }

        public PriorityBasedRoutingManager.ConflictResolutionResult getConflictResolutionResult() {
            return conflictResolutionResult;
        }

        public Duration getTotalProcessingTime() {
            return totalProcessingTime;
        }

        public boolean isSuccessful() {
            return isSuccessful;
        }

        public String getFailureReason() {
            return failureReason;
        }

        public boolean meetsPerformanceRequirements() {
            return totalProcessingTime.compareTo(Duration.ofSeconds(3)) <= 0;
        }
    }

    /**
     * Collaboration system health information
     */
    public static class CollaborationSystemHealth {
        private final com.positivity.agent.registry.RegistryHealthStatus registryHealth;
        private final boolean dependencyGraphValid;
        private final boolean contextManagerHealthy;

        public CollaborationSystemHealth(com.positivity.agent.registry.RegistryHealthStatus registryHealth,
                boolean dependencyGraphValid, boolean contextManagerHealthy) {
            this.registryHealth = registryHealth;
            this.dependencyGraphValid = dependencyGraphValid;
            this.contextManagerHealthy = contextManagerHealthy;
        }

        public com.positivity.agent.registry.RegistryHealthStatus getRegistryHealth() {
            return registryHealth;
        }

        public boolean isDependencyGraphValid() {
            return dependencyGraphValid;
        }

        public boolean isContextManagerHealthy() {
            return contextManagerHealthy;
        }

        public boolean isSystemHealthy() {
            return registryHealth.overallAvailability() > 0.8 && dependencyGraphValid && contextManagerHealthy;
        }
    }
}