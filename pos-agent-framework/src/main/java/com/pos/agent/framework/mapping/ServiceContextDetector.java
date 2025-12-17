package com.pos.agent.framework.mapping;

import com.positivity.agent.AgentConsultationRequest;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Service Context Detector
 * Automatically detects service context from requests and selects appropriate agents
 */
@Component
public class ServiceContextDetector {
    
    private static final Map<Pattern, String> SERVICE_PATTERNS = new HashMap<>();
    private static final Map<String, String> DOMAIN_KEYWORDS = new HashMap<>();
    
    static {
        initializePatterns();
        initializeDomainKeywords();
    }
    
    private static void initializePatterns() {
        // Service name patterns
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-api-gateway.*", Pattern.CASE_INSENSITIVE), "pos-api-gateway");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-security.*", Pattern.CASE_INSENSITIVE), "pos-security-service");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-catalog.*", Pattern.CASE_INSENSITIVE), "pos-catalog");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-customer.*", Pattern.CASE_INSENSITIVE), "pos-customer");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-inventory.*", Pattern.CASE_INSENSITIVE), "pos-inventory");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-vehicle-inventory.*", Pattern.CASE_INSENSITIVE), "pos-vehicle-inventory");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-order.*", Pattern.CASE_INSENSITIVE), "pos-order");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-invoice.*", Pattern.CASE_INSENSITIVE), "pos-invoice");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-price.*", Pattern.CASE_INSENSITIVE), "pos-price");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-accounting.*", Pattern.CASE_INSENSITIVE), "pos-accounting");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-work-order.*", Pattern.CASE_INSENSITIVE), "pos-work-order");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-people.*", Pattern.CASE_INSENSITIVE), "pos-people");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-location.*", Pattern.CASE_INSENSITIVE), "pos-location");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-events.*", Pattern.CASE_INSENSITIVE), "pos-events");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-event-receiver.*", Pattern.CASE_INSENSITIVE), "pos-event-receiver");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-image.*", Pattern.CASE_INSENSITIVE), "pos-image");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-vehicle-fitment.*", Pattern.CASE_INSENSITIVE), "pos-vehicle-fitment");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-vehicle-reference.*", Pattern.CASE_INSENSITIVE), "pos-vehicle-reference-data");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-inquiry.*", Pattern.CASE_INSENSITIVE), "pos-inquiry");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-shop-manager.*", Pattern.CASE_INSENSITIVE), "pos-shop-manager");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-service-discovery.*", Pattern.CASE_INSENSITIVE), "pos-service-discovery");
        SERVICE_PATTERNS.put(Pattern.compile(".*pos-config-server.*", Pattern.CASE_INSENSITIVE), "pos-config-server");
    }
    
    private static void initializeDomainKeywords() {
        // Architecture keywords
        DOMAIN_KEYWORDS.put("microservice", "microservices-architect");
        DOMAIN_KEYWORDS.put("architecture", "microservices-architect");
        DOMAIN_KEYWORDS.put("service-boundary", "microservices-architect");
        
        // Implementation keywords
        DOMAIN_KEYWORDS.put("spring-boot", "spring-boot-developer");
        DOMAIN_KEYWORDS.put("rest-api", "spring-boot-developer");
        DOMAIN_KEYWORDS.put("controller", "spring-boot-developer");
        DOMAIN_KEYWORDS.put("service-layer", "spring-boot-developer");
        
        // Security keywords
        DOMAIN_KEYWORDS.put("jwt", "security-specialist");
        DOMAIN_KEYWORDS.put("authentication", "security-specialist");
        DOMAIN_KEYWORDS.put("authorization", "security-specialist");
        DOMAIN_KEYWORDS.put("oauth", "security-specialist");
        
        // Database keywords
        DOMAIN_KEYWORDS.put("database", "database-per-service-specialist");
        DOMAIN_KEYWORDS.put("jpa", "database-per-service-specialist");
        DOMAIN_KEYWORDS.put("repository", "database-per-service-specialist");
        DOMAIN_KEYWORDS.put("migration", "database-per-service-specialist");
        
        // Event-driven keywords
        DOMAIN_KEYWORDS.put("event", "event-driven-specialist");
        DOMAIN_KEYWORDS.put("kafka", "event-driven-specialist");
        DOMAIN_KEYWORDS.put("messaging", "event-driven-specialist");
        DOMAIN_KEYWORDS.put("async", "event-driven-specialist");
        
        // Gateway keywords
        DOMAIN_KEYWORDS.put("gateway", "api-gateway-specialist");
        DOMAIN_KEYWORDS.put("routing", "api-gateway-specialist");
        DOMAIN_KEYWORDS.put("load-balancing", "api-gateway-specialist");
        
        // Testing keywords
        DOMAIN_KEYWORDS.put("test", "microservices-testing-specialist");
        DOMAIN_KEYWORDS.put("junit", "microservices-testing-specialist");
        DOMAIN_KEYWORDS.put("integration-test", "microservices-testing-specialist");
        
        // Deployment keywords
        DOMAIN_KEYWORDS.put("docker", "containerization-specialist");
        DOMAIN_KEYWORDS.put("kubernetes", "containerization-specialist");
        DOMAIN_KEYWORDS.put("deployment", "containerization-specialist");
        
        // Business domain keywords
        DOMAIN_KEYWORDS.put("vehicle", "business-domain");
        DOMAIN_KEYWORDS.put("automotive", "business-domain");
        DOMAIN_KEYWORDS.put("fitment", "business-domain");
        DOMAIN_KEYWORDS.put("pricing", "business-domain");
        
        // Observability keywords
        DOMAIN_KEYWORDS.put("monitoring", "observability-engineer");
        DOMAIN_KEYWORDS.put("metrics", "observability-engineer");
        DOMAIN_KEYWORDS.put("tracing", "observability-engineer");
        
        // CI/CD keywords
        DOMAIN_KEYWORDS.put("pipeline", "cicd-pipeline");
        DOMAIN_KEYWORDS.put("build", "cicd-pipeline");
        DOMAIN_KEYWORDS.put("deployment-strategy", "cicd-pipeline");
        
        // Configuration keywords
        DOMAIN_KEYWORDS.put("config", "configuration-management");
        DOMAIN_KEYWORDS.put("secrets", "configuration-management");
        DOMAIN_KEYWORDS.put("feature-flag", "configuration-management");
        
        // Resilience keywords
        DOMAIN_KEYWORDS.put("circuit-breaker", "resilience-engineering");
        DOMAIN_KEYWORDS.put("retry", "resilience-engineering");
        DOMAIN_KEYWORDS.put("failover", "resilience-engineering");
    }
    
    /**
     * Detect service context from request and return appropriate agent selection
     */
    public ServiceAgentSelection detectServiceContext(AgentConsultationRequest request) {
        String detectedService = detectServiceFromRequest(request);
        List<String> suggestedAgents = detectAgentsFromKeywords(request);
        
        if (detectedService != null) {
            String primaryAgent = ServiceAgentMapping.getPrimaryAgent(detectedService);
            List<String> mappedAgents = ServiceAgentMapping.getMappedAgents(detectedService);
            
            return new ServiceAgentSelection(detectedService, primaryAgent, mappedAgents, suggestedAgents);
        }
        
        // Fallback to keyword-based detection
        String primaryAgent = suggestedAgents.isEmpty() ? "spring-boot-developer" : suggestedAgents.get(0);
        return new ServiceAgentSelection(null, primaryAgent, suggestedAgents, suggestedAgents);
    }
    
    private String detectServiceFromRequest(AgentConsultationRequest request) {
        String requestText = buildRequestText(request);
        
        for (Map.Entry<Pattern, String> entry : SERVICE_PATTERNS.entrySet()) {
            if (entry.getKey().matcher(requestText).matches()) {
                return entry.getValue();
            }
        }
        
        return null;
    }
    
    private List<String> detectAgentsFromKeywords(AgentConsultationRequest request) {
        String requestText = buildRequestText(request).toLowerCase();
        Set<String> detectedAgents = new HashSet<>();
        
        for (Map.Entry<String, String> entry : DOMAIN_KEYWORDS.entrySet()) {
            if (requestText.contains(entry.getKey())) {
                detectedAgents.add(entry.getValue());
            }
        }
        
        return new ArrayList<>(detectedAgents);
    }
    
    private String buildRequestText(AgentConsultationRequest request) {
        StringBuilder text = new StringBuilder();
        
        if (request.domain() != null) {
            text.append(request.domain()).append(" ");
        }
        
        if (request.query() != null) {
            text.append(request.query()).append(" ");
        }
        
        // Add any additional context fields
        if (request.context() != null) {
            text.append(request.context().toString());
        }
        
        return text.toString();
    }
    
    /**
     * Service Agent Selection Result
     */
    public static class ServiceAgentSelection {
        private final String detectedService;
        private final String primaryAgent;
        private final List<String> mappedAgents;
        private final List<String> suggestedAgents;
        
        public ServiceAgentSelection(String detectedService, String primaryAgent, 
                                   List<String> mappedAgents, List<String> suggestedAgents) {
            this.detectedService = detectedService;
            this.primaryAgent = primaryAgent;
            this.mappedAgents = mappedAgents != null ? mappedAgents : Collections.emptyList();
            this.suggestedAgents = suggestedAgents != null ? suggestedAgents : Collections.emptyList();
        }
        
        public String getDetectedService() { return detectedService; }
        public String getPrimaryAgent() { return primaryAgent; }
        public List<String> getMappedAgents() { return mappedAgents; }
        public List<String> getSuggestedAgents() { return suggestedAgents; }
        
        public boolean hasDetectedService() { return detectedService != null; }
    }
}
