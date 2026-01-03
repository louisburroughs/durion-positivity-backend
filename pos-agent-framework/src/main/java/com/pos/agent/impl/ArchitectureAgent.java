package com.pos.agent.impl;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.ArchitectureContext;
import com.pos.agent.context.CICDContext;
import com.pos.agent.context.ConfigurationContext;
import com.pos.agent.context.EventDrivenContext;
import com.pos.agent.context.ResilienceContext;
import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.framework.model.AgentType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ArchitectureAgent provides architectural guidance and recommendations
 * for system design, pattern selection, and technology stack decisions.
 * 
 * Expected context keys:
 * - systemType (String): "microservices", "monolith", "serverless",
 * "event-driven"
 * - currentPatterns (List<String>): Existing architectural patterns in use
 * - requirements (List<String>): Non-functional requirements (scalability,
 * performance, etc.)
 * - constraints (Map<String,Object>): Technical or business constraints
 * - targetScale (String): Expected system scale (small, medium, large,
 * enterprise)
 */
public class ArchitectureAgent extends AbstractAgent {

    protected static final Map<String, AgentContext> CONTEXT_MAP = new ConcurrentHashMap<>();

    public ArchitectureAgent() {
        super(AgentType.ARCHITECTURE, List.of(
                "system-design",
                "pattern-selection",
                "technology-stack",
                "architectural-review",
                "scalability-design"));
    }

    @Override
    public AgentContext getOrCreateContext(String sessionId) {
        return CONTEXT_MAP.computeIfAbsent(sessionId,
                sid -> ArchitectureContext.builder().requestId(sessionId).build());
    }

    public AgentContext getOrCreateEventDrivenContext(String sessionId) {
        return EventDrivenArchitectureAgent.CONTEXT_MAP.computeIfAbsent(sessionId,
                sid -> EventDrivenContext.builder().requestId(sessionId).build());
    }

    public AgentContext getOrCreateCICDContext(String sessionId) {
        return CICDPipelineAgent.CONTEXT_MAP.computeIfAbsent(sessionId,
                sid -> CICDContext.builder().requestId(sessionId).build());
    }

    public AgentContext getOrCreateConfigurationContext(String sessionId) {
        return ConfigurationManagementAgent.CONTEXT_MAP.computeIfAbsent(sessionId,
                sid -> ConfigurationContext.builder().requestId(sessionId).build());
    }

    public AgentContext getOrCreateResilienceContext(String sessionId) {
        return ResilienceEngineeringAgent.CONTEXT_MAP.computeIfAbsent(sessionId,
                sid -> ResilienceContext.builder().requestId(sessionId).build());
    }

    @Override
    protected AgentResponse doProcessRequest(AgentRequest request) {
        // Extract properties from AgentContext
        AgentContext agentContext = request.getAgentContext();
        Map<String, Object> requestContext = agentContext != null ? agentContext.getProperties() : new HashMap<>();

        // Extract architecture context with defaults
        String systemType = (String) requestContext.getOrDefault("systemType", "unknown");
        List<String> currentPatterns = extractStringList(requestContext.get("currentPatterns"));
        List<String> requirements = extractStringList(requestContext.get("requirements"));
        Map<String, Object> constraints = extractMap(requestContext.get("constraints"));
        String targetScale = (String) requestContext.getOrDefault("targetScale", "medium");

        // Perform architecture analysis
        ArchitectureAnalysis analysis = analyzeArchitecture(
                request.getAgentContext().getDescription(),
                systemType,
                currentPatterns,
                requirements,
                constraints,
                targetScale);

        // Build context map for response
        agentContext.getProperties().put("patterns_evaluated", analysis.getPatternsEvaluated());
        agentContext.getProperties().put("trade_offs", analysis.getTradeOffs());
        agentContext.getProperties().put("system_type", systemType);
        agentContext.getProperties().put("target_scale", targetScale);

        return AgentResponse.builder()
                .status(AgentProcessingState.SUCCESS)
                .output(analysis.getSummary())
                .confidence(analysis.getConfidence())
                .success(true)
                .recommendations(analysis.getRecommendations())
                .agentContext(agentContext)
                .build();
    }

    private ArchitectureAnalysis analyzeArchitecture(
            String description,
            String systemType,
            List<String> currentPatterns,
            List<String> requirements,
            Map<String, Object> constraints,
            String targetScale) {
        ArchitectureAnalysis analysis = new ArchitectureAnalysis();

        // Analyze system type and recommend patterns
        List<String> recommendedPatterns = recommendPatternsForSystemType(systemType, targetScale);
        analysis.addPatternsEvaluated(recommendedPatterns);

        // Generate recommendations based on requirements
        List<String> recommendations = new ArrayList<>();
        recommendations.add(String.format("Implement %s architecture for %s to %s",
                systemType, description, getSystemTypeRationale(systemType)));

        // Add pattern-specific recommendations
        for (String pattern : recommendedPatterns) {
            if (!currentPatterns.contains(pattern)) {
                recommendations.add(String.format("Implement %s pattern for improved %s",
                        pattern, getPatternBenefit(pattern)));
            }
        }

        // Analyze requirements
        if (requirements.contains("scalability") || requirements.contains("high-availability")) {
            recommendations.add("Implement horizontal scaling with load balancing");
            recommendations.add("Consider distributed caching (Redis/Memcached) for performance");
            analysis.addTradeOff("Scalability vs Complexity: Distributed systems increase operational overhead");
        }

        if (requirements.contains("performance")) {
            recommendations.add("Use async/non-blocking I/O patterns where possible");
            recommendations.add("Implement read replicas for database scalability");
            analysis.addTradeOff("Performance vs Cost: High-performance infrastructure increases operational costs");
        }

        if (requirements.contains("security")) {
            recommendations.add("Implement API gateway with OAuth2/JWT authentication");
            recommendations.add("Use service mesh for zero-trust network security");
            analysis.addTradeOff(
                    "Security vs Developer Velocity: Additional security layers slow development iteration");
        }

        // Analyze constraints
        if (constraints.containsKey("budget") && "limited".equals(constraints.get("budget"))) {
            recommendations.add("Consider serverless architecture to optimize costs");
            recommendations.add("Use managed services to reduce operational overhead");
        }

        if (constraints.containsKey("team_size") && "small".equals(constraints.get("team_size"))) {
            recommendations.add("Prefer monolithic or modular monolith over microservices");
            recommendations.add("Use platform-as-a-service solutions to minimize DevOps burden");
            analysis.addTradeOff("Team Size vs Architecture Complexity: Small teams struggle with distributed systems");
        }

        // Scale-specific recommendations
        switch (targetScale.toLowerCase()) {
            case "enterprise":
                recommendations.add("Implement comprehensive observability (metrics, logs, traces)");
                recommendations.add("Design for multi-region deployment and disaster recovery");
                analysis.addTradeOff(
                        "Enterprise Scale vs Time-to-Market: Complex infrastructure delays initial delivery");
                break;
            case "large":
                recommendations.add("Implement event-driven architecture for loose coupling");
                recommendations.add("Use CQRS pattern for read/write optimization");
                break;
            case "small":
                recommendations.add("Start with simple architecture, plan for evolution");
                recommendations.add("Avoid premature optimization and over-engineering");
                analysis.addTradeOff(
                        "Simplicity vs Future Growth: Simple architectures may require refactoring at scale");
                break;
        }

        // Add monitoring and observability recommendations
        recommendations.add("Implement health checks and circuit breakers for resilience");
        recommendations.add("Implement automated testing pipeline (unit, integration, performance)");

        analysis.setRecommendations(recommendations);
        analysis.setSummary(generateSummary(description, systemType, recommendedPatterns, requirements));
        analysis.setConfidence(calculateConfidence(systemType, requirements, constraints));

        return analysis;
    }

    private List<String> recommendPatternsForSystemType(String systemType, String targetScale) {
        List<String> patterns = new ArrayList<>();

        switch (systemType.toLowerCase()) {
            case "microservices":
                patterns.addAll(List.of("API Gateway", "Service Discovery", "Circuit Breaker",
                        "Event Sourcing", "CQRS", "Saga Pattern"));
                break;
            case "monolith":
                patterns.addAll(List.of("Layered Architecture", "Repository Pattern",
                        "Domain-Driven Design", "CQRS"));
                break;
            case "serverless":
                patterns.addAll(List.of("Function as a Service", "Event-Driven Architecture",
                        "Backend for Frontend", "Strangler Fig"));
                break;
            case "event-driven":
                patterns.addAll(List.of("Event Sourcing", "CQRS", "Saga Pattern",
                        "Publish-Subscribe", "Event Streaming"));
                break;
            default:
                patterns.addAll(List.of("Layered Architecture", "Repository Pattern", "MVC"));
        }

        // Add scale-specific patterns
        if ("enterprise".equals(targetScale) || "large".equals(targetScale)) {
            patterns.addAll(List.of("Service Mesh", "API Gateway", "Distributed Tracing"));
        }

        return patterns;
    }

    private String getSystemTypeRationale(String systemType) {
        return switch (systemType.toLowerCase()) {
            case "microservices" -> "enables independent deployment, scaling, and technology diversity";
            case "monolith" -> "simplifies development, deployment, and operations for smaller teams";
            case "serverless" -> "optimizes cost with pay-per-use and eliminates infrastructure management";
            case "event-driven" -> "provides loose coupling, scalability, and real-time processing capabilities";
            default -> "provides a solid foundation for application development";
        };
    }

    private String getPatternBenefit(String pattern) {
        return switch (pattern) {
            case "API Gateway" -> "centralized API management and security";
            case "Service Discovery" -> "dynamic service location and load balancing";
            case "Circuit Breaker" -> "fault tolerance and graceful degradation";
            case "Event Sourcing" -> "complete audit trail and temporal queries";
            case "CQRS" -> "optimized read/write performance";
            case "Saga Pattern" -> "distributed transaction management";
            case "Service Mesh" -> "traffic management and security";
            case "Repository Pattern" -> "data access abstraction";
            case "Domain-Driven Design" -> "business logic organization";
            default -> "system quality attributes";
        };
    }

    private String generateSummary(String description, String systemType,
            List<String> patterns, List<String> requirements) {
        StringBuilder summary = new StringBuilder();
        summary.append(String.format("Architecture Analysis for '%s':\n\n", description));
        summary.append(String.format("System Type: %s\n", systemType));
        summary.append(String.format("Recommended Patterns: %s\n", String.join(", ", patterns)));

        if (!requirements.isEmpty()) {
            summary.append(String.format("Key Requirements: %s\n", String.join(", ", requirements)));
        }

        summary.append("\nThis analysis provides architectural guidance based on industry best practices. ");
        summary.append("Consider your specific context, team capabilities, and constraints when implementing.");

        return summary.toString();
    }

    private double calculateConfidence(String systemType, List<String> requirements,
            Map<String, Object> constraints) {
        double confidence = 0.7; // Base confidence

        // Increase confidence if we have more context
        if (!"unknown".equals(systemType))
            confidence += 0.1;
        if (!requirements.isEmpty())
            confidence += 0.05 * Math.min(requirements.size(), 3);
        if (!constraints.isEmpty())
            confidence += 0.05;

        return Math.min(confidence, 0.95); // Cap at 0.95
    }

    private List<String> extractStringList(Object obj) {
        if (obj instanceof List<?>) {
            return ((List<?>) obj).stream()
                    .filter(item -> item instanceof String)
                    .map(item -> (String) item)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractMap(Object obj) {
        if (obj instanceof Map<?, ?>) {
            return (Map<String, Object>) obj;
        }
        return new HashMap<>();
    }

    /**
     * Internal class to hold architecture analysis results
     */
    private static class ArchitectureAnalysis {
        private String summary;
        private double confidence;
        private List<String> recommendations = new ArrayList<>();
        private List<String> patternsEvaluated = new ArrayList<>();
        private List<String> tradeOffs = new ArrayList<>();

        public String getSummary() {
            return summary;
        }

        public void setSummary(String summary) {
            this.summary = summary;
        }

        public double getConfidence() {
            return confidence;
        }

        public void setConfidence(double confidence) {
            this.confidence = confidence;
        }

        public List<String> getRecommendations() {
            return recommendations;
        }

        public void setRecommendations(List<String> recommendations) {
            this.recommendations = recommendations;
        }

        public List<String> getPatternsEvaluated() {
            return patternsEvaluated;
        }

        public void addPatternsEvaluated(List<String> patterns) {
            this.patternsEvaluated.addAll(patterns);
        }

        public List<String> getTradeOffs() {
            return tradeOffs;
        }

        public void addTradeOff(String tradeOff) {
            this.tradeOffs.add(tradeOff);
        }
    }

    @Override
    public void removeContext(String sessionId) {
        CONTEXT_MAP.remove(sessionId);
        EventDrivenArchitectureAgent.CONTEXT_MAP.remove(sessionId);
        CICDPipelineAgent.CONTEXT_MAP.remove(sessionId);
        ConfigurationManagementAgent.CONTEXT_MAP.remove(sessionId);
        ResilienceEngineeringAgent.CONTEXT_MAP.remove(sessionId);

    }
}
