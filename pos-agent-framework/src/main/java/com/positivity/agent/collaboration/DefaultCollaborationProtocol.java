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
 * Default implementation of agent collaboration protocol
 */
@Component
public class DefaultCollaborationProtocol implements AgentCollaborationProtocol {
    
    private static final Logger logger = LoggerFactory.getLogger(DefaultCollaborationProtocol.class);
    private static final double CONSISTENCY_THRESHOLD = 0.8; // 80% consistency required
    
    private final AgentRegistry agentRegistry;
    private final Map<String, List<String>> workflowMappings;
    
    public DefaultCollaborationProtocol(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
        this.workflowMappings = initializeWorkflowMappings();
    }
    
    @Override
    public CompletableFuture<CollaborativeGuidanceResponse> coordinateConsultation(
            AgentConsultationRequest request, List<String> participatingAgents) {
        
        Instant start = Instant.now();
        logger.debug("Starting collaborative consultation for request {} with agents: {}", 
                request.requestId(), participatingAgents);
        
        // Get all participating agents
        List<Agent> agents = participatingAgents.stream()
                .map(agentRegistry::getAgent)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(Agent::isAvailable)
                .collect(Collectors.toList());
        
        if (agents.isEmpty()) {
            return CompletableFuture.completedFuture(
                CollaborativeGuidanceResponse.failure(request.requestId(), participatingAgents, 
                    "No available agents found", Duration.between(start, Instant.now()))
            );
        }
        
        // Execute consultations in parallel
        List<CompletableFuture<AgentGuidanceResponse>> futures = agents.stream()
                .map(agent -> agent.provideGuidance(request))
                .collect(Collectors.toList());
        
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    List<AgentGuidanceResponse> responses = futures.stream()
                            .map(CompletableFuture::join)
                            .filter(AgentGuidanceResponse::isSuccessful)
                            .collect(Collectors.toList());
                    
                    if (responses.isEmpty()) {
                        return CollaborativeGuidanceResponse.failure(request.requestId(), participatingAgents,
                                "All agents failed to provide guidance", Duration.between(start, Instant.now()));
                    }
                    
                    // Validate consistency
                    ConsistencyValidationResult consistency = validateConsistency(responses);
                    
                    // Resolve conflicts if needed
                    AgentGuidanceResponse resolvedResponse = consistency.isConsistent() ? 
                            responses.get(0) : resolveConflicts(responses);
                    
                    // Consolidate guidance
                    String consolidatedGuidance = consolidateGuidance(responses);
                    List<String> consolidatedRecommendations = consolidateRecommendations(responses);
                    double overallConfidence = calculateOverallConfidence(responses);
                    
                    Duration totalTime = Duration.between(start, Instant.now());
                    
                    // Check if total time exceeds 3-second threshold
                    if (totalTime.compareTo(Duration.ofSeconds(3)) > 0) {
                        logger.warn("Collaborative consultation exceeded 3-second threshold: {}", totalTime);
                    }
                    
                    return CollaborativeGuidanceResponse.success(
                            request.requestId(),
                            participatingAgents,
                            consolidatedGuidance,
                            overallConfidence,
                            consolidatedRecommendations,
                            responses,
                            consistency,
                            totalTime
                    );
                });
    }
    
    @Override
    public ConsistencyValidationResult validateConsistency(List<AgentGuidanceResponse> responses) {
        Instant start = Instant.now();
        
        if (responses.size() < 2) {
            return ConsistencyValidationResult.consistent(1.0, 
                    List.of("Single response - no conflicts possible"), 
                    Duration.between(start, Instant.now()));
        }
        
        List<String> conflicts = new ArrayList<>();
        List<String> agreements = new ArrayList<>();
        
        // Simple consistency check based on confidence levels and recommendation overlap
        double avgConfidence = responses.stream()
                .mapToDouble(AgentGuidanceResponse::confidence)
                .average()
                .orElse(0.0);
        
        double confidenceVariance = responses.stream()
                .mapToDouble(r -> Math.pow(r.confidence() - avgConfidence, 2))
                .average()
                .orElse(0.0);
        
        // Check for recommendation overlap
        Set<String> allRecommendations = responses.stream()
                .flatMap(r -> r.recommendations().stream())
                .collect(Collectors.toSet());
        
        Map<String, Long> recommendationCounts = responses.stream()
                .flatMap(r -> r.recommendations().stream())
                .collect(Collectors.groupingBy(r -> r, Collectors.counting()));
        
        long agreementCount = recommendationCounts.values().stream()
                .filter(count -> count > 1)
                .count();
        
        double consistencyScore = allRecommendations.isEmpty() ? 1.0 : 
                (double) agreementCount / allRecommendations.size();
        
        // Add confidence variance to consistency calculation
        if (confidenceVariance > 0.1) { // High variance indicates inconsistency
            consistencyScore *= (1.0 - confidenceVariance);
            conflicts.add("High variance in confidence levels: " + confidenceVariance);
        }
        
        if (consistencyScore >= CONSISTENCY_THRESHOLD) {
            agreements.add("Recommendations show " + (consistencyScore * 100) + "% agreement");
            return ConsistencyValidationResult.consistent(consistencyScore, agreements, 
                    Duration.between(start, Instant.now()));
        } else {
            conflicts.add("Low recommendation agreement: " + (consistencyScore * 100) + "%");
            return ConsistencyValidationResult.inconsistent(consistencyScore, conflicts, 
                    Duration.between(start, Instant.now()));
        }
    }
    
    @Override
    public AgentGuidanceResponse resolveConflicts(List<AgentGuidanceResponse> responses) {
        // Simple conflict resolution: choose response with highest confidence
        AgentGuidanceResponse bestResponse = responses.stream()
                .max(Comparator.comparing(AgentGuidanceResponse::confidence))
                .orElse(responses.get(0));
        
        logger.info("Resolved conflicts by selecting response from agent {} with confidence {}", 
                bestResponse.agentId(), bestResponse.confidence());
        
        return bestResponse;
    }
    
    @Override
    public List<String> getCollaborationWorkflow(String requestDomain) {
        return workflowMappings.getOrDefault(requestDomain, List.of());
    }
    
    private String consolidateGuidance(List<AgentGuidanceResponse> responses) {
        if (responses.size() == 1) {
            return responses.get(0).guidance();
        }
        
        // Simple consolidation: combine guidance from all responses
        StringBuilder consolidated = new StringBuilder();
        consolidated.append("Collaborative Guidance:\n\n");
        
        for (int i = 0; i < responses.size(); i++) {
            AgentGuidanceResponse response = responses.get(i);
            consolidated.append(String.format("Agent %s (Confidence: %.2f):\n%s\n\n", 
                    response.agentId(), response.confidence(), response.guidance()));
        }
        
        return consolidated.toString();
    }
    
    private List<String> consolidateRecommendations(List<AgentGuidanceResponse> responses) {
        return responses.stream()
                .flatMap(r -> r.recommendations().stream())
                .distinct()
                .collect(Collectors.toList());
    }
    
    private double calculateOverallConfidence(List<AgentGuidanceResponse> responses) {
        return responses.stream()
                .mapToDouble(AgentGuidanceResponse::confidence)
                .average()
                .orElse(0.0);
    }
    
    private Map<String, List<String>> initializeWorkflowMappings() {
        Map<String, List<String>> mappings = new HashMap<>();
        
        // Define collaboration workflows for different domains
        mappings.put("microservice-development", List.of(
                "architecture-agent", "spring-boot-developer-agent", 
                "api-gateway-agent", "data-access-agent", "testing-agent"
        ));
        
        mappings.put("deployment-pipeline", List.of(
                "devops-agent", "security-agent", "sre-agent", "performance-agent"
        ));
        
        mappings.put("documentation-workflow", List.of(
                "documentation-agent", "api-documentation-agent"
        ));
        
        mappings.put("security", List.of(
                "security-agent", "architecture-agent"
        ));
        
        mappings.put("performance", List.of(
                "performance-agent", "sre-agent", "data-access-agent"
        ));
        
        return mappings;
    }
}