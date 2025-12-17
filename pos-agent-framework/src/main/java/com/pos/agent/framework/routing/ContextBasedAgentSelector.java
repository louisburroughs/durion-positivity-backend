package com.pos.agent.framework.routing;

import com.positivity.agent.AgentConsultationRequest;
import com.pos.agent.framework.model.AgentRoutingResult;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.Agent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Context-based agent selection algorithm that analyzes request context
 * to determine the most suitable agent for handling the request.
 */
@Component
public class ContextBasedAgentSelector {
    
    private static final Logger logger = Logger.getLogger(ContextBasedAgentSelector.class.getName());
    
    @Autowired
    private AgentRegistry agentRegistry;
    
    // Context keywords mapped to agent types
    private static final Map<String, List<String>> CONTEXT_KEYWORDS = Map.ofEntries(
        Map.entry("spring-boot-developer", Arrays.asList("spring-boot", "rest-api", "jpa", "hibernate", "controller", "service", "repository")),
        Map.entry("microservices-architect", Arrays.asList("microservice", "architecture", "service-boundary", "integration", "scalability")),
        Map.entry("security-specialist", Arrays.asList("jwt", "authentication", "authorization", "security", "oauth", "ssl", "tls")),
        Map.entry("api-gateway-specialist", Arrays.asList("gateway", "routing", "load-balancing", "api-management", "zuul", "spring-cloud-gateway")),
        Map.entry("database-per-service-specialist", Arrays.asList("database", "postgresql", "mysql", "migration", "schema", "flyway", "liquibase")),
        Map.entry("containerization-specialist", Arrays.asList("docker", "kubernetes", "container", "deployment", "orchestration", "helm")),
        Map.entry("observability-engineer", Arrays.asList("monitoring", "tracing", "metrics", "prometheus", "grafana", "opentelemetry")),
        Map.entry("microservices-testing-specialist", Arrays.asList("testing", "junit", "testcontainers", "integration-test", "contract-test")),
        Map.entry("event-driven-specialist", Arrays.asList("kafka", "rabbitmq", "event", "messaging", "async", "event-sourcing", "cqrs")),
        Map.entry("cicd-pipeline-specialist", Arrays.asList("ci-cd", "pipeline", "jenkins", "github-actions", "deployment", "automation")),
        Map.entry("configuration-management-specialist", Arrays.asList("config", "configuration", "spring-cloud-config", "consul", "vault", "secrets")),
        Map.entry("resilience-engineering-specialist", Arrays.asList("circuit-breaker", "retry", "resilience", "chaos", "failover", "disaster-recovery")),
        Map.entry("business-domain-specialist", Arrays.asList("pos", "inventory", "customer", "order", "invoice", "automotive", "vehicle")),
        Map.entry("documentation-specialist", Arrays.asList("documentation", "openapi", "swagger", "api-docs", "readme")),
        Map.entry("spring-boot-pair-navigator", Arrays.asList("refactoring", "code-review", "best-practices", "pair-programming"))
    );
    
    /**
     * Selects the best agent based on request context analysis.
     */
    public AgentRoutingResult selectAgent(AgentConsultationRequest request) {
        logger.info("Analyzing context for agent selection: " + request.query());
        
        // Analyze context and score agents
        Map<String, Double> agentScores = analyzeContext(request);
        
        // Find the best available agent
        Optional<String> bestAgent = findBestAvailableAgent(agentScores);
        
        if (bestAgent.isPresent()) {
            double score = agentScores.get(bestAgent.get());
            String reason = String.format("Context analysis (score: %.2f)", score);
            return AgentRoutingResult.success(bestAgent.get(), "context-based", reason);
        }
        
        return AgentRoutingResult.failure("No suitable agent found based on context analysis", "unknown");
    }
    
    /**
     * Analyzes request context and returns agent scores.
     */
    private Map<String, Double> analyzeContext(AgentConsultationRequest request) {
        Map<String, Double> scores = new HashMap<>();
        
        String contextText = (request.query() + " " + request.domain()).toLowerCase();
        
        for (Map.Entry<String, List<String>> entry : CONTEXT_KEYWORDS.entrySet()) {
            String agentType = entry.getKey();
            List<String> keywords = entry.getValue();
            
            double score = calculateContextScore(contextText, keywords);
            if (score > 0) {
                scores.put(agentType, score);
            }
        }
        
        return scores;
    }
    
    /**
     * Calculates context score based on keyword matching.
     */
    private double calculateContextScore(String contextText, List<String> keywords) {
        double score = 0.0;
        int totalKeywords = keywords.size();
        
        for (String keyword : keywords) {
            if (contextText.contains(keyword)) {
                // Base score for keyword match
                score += 1.0;
                
                // Bonus for exact word match (not just substring)
                if (contextText.matches(".*\\b" + keyword + "\\b.*")) {
                    score += 0.5;
                }
                
                // Bonus for multiple occurrences
                long occurrences = contextText.split(keyword, -1).length - 1;
                if (occurrences > 1) {
                    score += (occurrences - 1) * 0.2;
                }
            }
        }
        
        // Normalize score by total keywords
        return score / totalKeywords;
    }
    
    /**
     * Finds the best available agent from scored agents.
     */
    private Optional<String> findBestAvailableAgent(Map<String, Double> agentScores) {
        return agentScores.entrySet().stream()
            .filter(entry -> isAgentAvailable(entry.getKey()))
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey);
    }
    
    /**
     * Gets multiple agent suggestions based on context analysis.
     */
    public List<String> getSuggestedAgents(AgentConsultationRequest request, int maxSuggestions) {
        Map<String, Double> agentScores = analyzeContext(request);
        
        return agentScores.entrySet().stream()
            .filter(entry -> isAgentAvailable(entry.getKey()))
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .limit(maxSuggestions)
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }
    
    /**
     * Analyzes context complexity to determine if multiple agents are needed.
     */
    public boolean requiresMultipleAgents(AgentConsultationRequest request) {
        Map<String, Double> agentScores = analyzeContext(request);
        
        // If multiple agents have high scores, might need collaboration
        long highScoreAgents = agentScores.values().stream()
            .mapToLong(score -> score > 0.5 ? 1 : 0)
            .sum();
            
        return highScoreAgents > 1;
    }
    
    /**
     * Gets context analysis details for debugging and optimization.
     */
    public ContextAnalysis getContextAnalysis(AgentConsultationRequest request) {
        Map<String, Double> agentScores = analyzeContext(request);
        List<String> suggestedAgents = getSuggestedAgents(request, 5);
        boolean multipleAgentsNeeded = requiresMultipleAgents(request);
        
        return new ContextAnalysis(agentScores, suggestedAgents, multipleAgentsNeeded);
    }
    
    /**
     * Checks if an agent is available.
     */
    private boolean isAgentAvailable(String agentType) {
        try {
            return agentRegistry.getAvailableAgents().stream()
                .anyMatch(agent -> agent.getId().equals(agentType) || 
                         agent.getClass().getSimpleName().toLowerCase().contains(agentType.toLowerCase()));
        } catch (Exception e) {
            logger.warning("Error checking agent availability for " + agentType + ": " + e.getMessage());
            return false;
        }
    }
    
    /**
     * Context analysis result for debugging and monitoring.
     */
    public static class ContextAnalysis {
        private final Map<String, Double> agentScores;
        private final List<String> suggestedAgents;
        private final boolean multipleAgentsNeeded;
        
        public ContextAnalysis(Map<String, Double> agentScores, List<String> suggestedAgents, boolean multipleAgentsNeeded) {
            this.agentScores = agentScores;
            this.suggestedAgents = suggestedAgents;
            this.multipleAgentsNeeded = multipleAgentsNeeded;
        }
        
        public Map<String, Double> getAgentScores() { return agentScores; }
        public List<String> getSuggestedAgents() { return suggestedAgents; }
        public boolean isMultipleAgentsNeeded() { return multipleAgentsNeeded; }
    }
}
