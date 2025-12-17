package com.positivity.agent.collaboration;

import com.positivity.agent.AgentGuidanceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Enhanced multi-agent consistency validation system
 * Implements comprehensive consistency checking across multiple dimensions
 */
@Component
public class MultiAgentConsistencyValidator {

    private static final Logger logger = LoggerFactory.getLogger(MultiAgentConsistencyValidator.class);

    // Consistency thresholds for different validation dimensions
    private static final double RECOMMENDATION_CONSISTENCY_THRESHOLD = 0.7;
    private static final double CONFIDENCE_CONSISTENCY_THRESHOLD = 0.8;
    private static final double SEMANTIC_CONSISTENCY_THRESHOLD = 0.6;
    private static final double ARCHITECTURAL_CONSISTENCY_THRESHOLD = 0.9;

    // Keywords for different consistency dimensions
    private final Set<String> architecturalKeywords = Set.of(
            "microservice", "domain", "boundary", "integration", "pattern", "architecture");

    private final Set<String> securityKeywords = Set.of(
            "authentication", "authorization", "security", "jwt", "encryption", "owasp");

    private final Set<String> performanceKeywords = Set.of(
            "performance", "optimization", "caching", "scaling", "latency", "throughput");

    /**
     * Perform comprehensive consistency validation across multiple dimensions
     */
    public EnhancedConsistencyValidationResult validateMultiDimensionalConsistency(
            List<AgentGuidanceResponse> responses) {

        Instant start = Instant.now();

        if (responses.size() < 2) {
            return EnhancedConsistencyValidationResult.singleResponse(Duration.between(start, Instant.now()));
        }

        // Validate different consistency dimensions
        ConsistencyDimension recommendationConsistency = validateRecommendationConsistency(responses);
        ConsistencyDimension confidenceConsistency = validateConfidenceConsistency(responses);
        ConsistencyDimension semanticConsistency = validateSemanticConsistency(responses);
        ConsistencyDimension architecturalConsistency = validateArchitecturalConsistency(responses);
        ConsistencyDimension crossDomainConsistency = validateCrossDomainConsistency(responses);

        // Calculate overall consistency score
        double overallScore = calculateOverallConsistencyScore(
                recommendationConsistency, confidenceConsistency, semanticConsistency,
                architecturalConsistency, crossDomainConsistency);

        // Identify conflicts and agreements
        List<String> conflicts = identifyConflicts(responses,
                recommendationConsistency, confidenceConsistency, semanticConsistency,
                architecturalConsistency, crossDomainConsistency);

        List<String> agreements = identifyAgreements(responses,
                recommendationConsistency, confidenceConsistency, semanticConsistency,
                architecturalConsistency, crossDomainConsistency);

        Duration validationTime = Duration.between(start, Instant.now());

        return new EnhancedConsistencyValidationResult(
                overallScore >= RECOMMENDATION_CONSISTENCY_THRESHOLD,
                overallScore,
                conflicts,
                agreements,
                recommendationConsistency,
                confidenceConsistency,
                semanticConsistency,
                architecturalConsistency,
                crossDomainConsistency,
                validationTime);
    }

    /**
     * Validate consistency of recommendations across agents
     */
    private ConsistencyDimension validateRecommendationConsistency(List<AgentGuidanceResponse> responses) {
        Map<String, Long> recommendationCounts = responses.stream()
                .flatMap(r -> r.recommendations().stream())
                .collect(Collectors.groupingBy(r -> r.toLowerCase(), Collectors.counting()));

        long totalRecommendations = recommendationCounts.values().stream().mapToLong(Long::longValue).sum();
        long agreementCount = recommendationCounts.values().stream()
                .filter(count -> count > 1)
                .mapToLong(Long::longValue)
                .sum();

        double score = totalRecommendations > 0 ? (double) agreementCount / totalRecommendations : 1.0;

        return new ConsistencyDimension("recommendation", score,
                score >= RECOMMENDATION_CONSISTENCY_THRESHOLD,
                Map.of("recommendation_counts", recommendationCounts));
    }

    /**
     * Validate consistency of confidence levels across agents
     */
    private ConsistencyDimension validateConfidenceConsistency(List<AgentGuidanceResponse> responses) {
        List<Double> confidences = responses.stream()
                .map(AgentGuidanceResponse::confidence)
                .collect(Collectors.toList());

        double avgConfidence = confidences.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = confidences.stream()
                .mapToDouble(c -> Math.pow(c - avgConfidence, 2))
                .average()
                .orElse(0.0);

        double score = 1.0 - Math.min(variance, 1.0); // Lower variance = higher consistency

        Map<String, Object> details = Map.of(
                "average_confidence", avgConfidence,
                "variance", variance,
                "confidence_range", confidences.stream().mapToDouble(Double::doubleValue).max().orElse(0.0) -
                        confidences.stream().mapToDouble(Double::doubleValue).min().orElse(0.0));

        return new ConsistencyDimension("confidence", score,
                score >= CONFIDENCE_CONSISTENCY_THRESHOLD, details);
    }

    /**
     * Validate semantic consistency of guidance content
     */
    private ConsistencyDimension validateSemanticConsistency(List<AgentGuidanceResponse> responses) {
        // Simple semantic consistency based on keyword overlap
        Map<String, Set<String>> agentKeywords = responses.stream()
                .collect(Collectors.toMap(
                        AgentGuidanceResponse::agentId,
                        r -> extractKeywords(r.guidance())));

        Set<String> commonKeywords = agentKeywords.values().stream()
                .reduce((set1, set2) -> {
                    Set<String> intersection = new HashSet<>(set1);
                    intersection.retainAll(set2);
                    return intersection;
                })
                .orElse(Set.of());

        Set<String> allKeywords = agentKeywords.values().stream()
                .flatMap(Set::stream)
                .collect(Collectors.toSet());

        double score = allKeywords.isEmpty() ? 1.0 : (double) commonKeywords.size() / allKeywords.size();

        Map<String, Object> details = Map.of(
                "common_keywords", commonKeywords,
                "total_keywords", allKeywords.size(),
                "overlap_ratio", score);

        return new ConsistencyDimension("semantic", score,
                score >= SEMANTIC_CONSISTENCY_THRESHOLD, details);
    }

    /**
     * Validate architectural consistency across agents
     */
    private ConsistencyDimension validateArchitecturalConsistency(List<AgentGuidanceResponse> responses) {
        List<String> architecturalGuidance = responses.stream()
                .filter(r -> containsArchitecturalContent(r.guidance()))
                .map(AgentGuidanceResponse::guidance)
                .collect(Collectors.toList());

        if (architecturalGuidance.size() < 2) {
            return new ConsistencyDimension("architectural", 1.0, true,
                    Map.of("architectural_responses", architecturalGuidance.size()));
        }

        // Check for conflicting architectural patterns
        Set<String> patterns = architecturalGuidance.stream()
                .flatMap(guidance -> extractArchitecturalPatterns(guidance).stream())
                .collect(Collectors.toSet());

        Map<String, Long> patternCounts = architecturalGuidance.stream()
                .flatMap(guidance -> extractArchitecturalPatterns(guidance).stream())
                .collect(Collectors.groupingBy(p -> p, Collectors.counting()));

        long agreementCount = patternCounts.values().stream()
                .filter(count -> count > 1)
                .count();

        double score = patterns.isEmpty() ? 1.0 : (double) agreementCount / patterns.size();

        return new ConsistencyDimension("architectural", score,
                score >= ARCHITECTURAL_CONSISTENCY_THRESHOLD,
                Map.of("patterns", patterns, "pattern_agreements", agreementCount));
    }

    /**
     * Validate cross-domain consistency (security, performance, etc.)
     */
    private ConsistencyDimension validateCrossDomainConsistency(List<AgentGuidanceResponse> responses) {
        Map<String, List<String>> domainGuidance = new HashMap<>();

        for (AgentGuidanceResponse response : responses) {
            String guidance = response.guidance().toLowerCase();

            if (containsKeywords(guidance, securityKeywords)) {
                domainGuidance.computeIfAbsent("security", k -> new ArrayList<>()).add(response.agentId());
            }
            if (containsKeywords(guidance, performanceKeywords)) {
                domainGuidance.computeIfAbsent("performance", k -> new ArrayList<>()).add(response.agentId());
            }
        }

        // Check for cross-domain conflicts (e.g., security vs performance trade-offs)
        boolean hasConflicts = detectCrossDomainConflicts(responses, domainGuidance);

        double score = hasConflicts ? 0.5 : 1.0; // Simple binary scoring for now

        return new ConsistencyDimension("cross_domain", score, !hasConflicts,
                Map.of("domain_coverage", domainGuidance, "conflicts_detected", hasConflicts));
    }

    private double calculateOverallConsistencyScore(ConsistencyDimension... dimensions) {
        return Arrays.stream(dimensions)
                .mapToDouble(ConsistencyDimension::getScore)
                .average()
                .orElse(0.0);
    }

    private List<String> identifyConflicts(List<AgentGuidanceResponse> responses,
            ConsistencyDimension... dimensions) {
        List<String> conflicts = new ArrayList<>();

        for (ConsistencyDimension dimension : dimensions) {
            if (!dimension.isConsistent()) {
                conflicts.add(String.format("%s consistency below threshold: %.2f",
                        dimension.getName(), dimension.getScore()));
            }
        }

        // Add specific conflicts based on response analysis
        conflicts.addAll(detectSpecificConflicts(responses));

        return conflicts;
    }

    private List<String> identifyAgreements(List<AgentGuidanceResponse> responses,
            ConsistencyDimension... dimensions) {
        List<String> agreements = new ArrayList<>();

        for (ConsistencyDimension dimension : dimensions) {
            if (dimension.isConsistent()) {
                agreements.add(String.format("%s shows good consistency: %.2f",
                        dimension.getName(), dimension.getScore()));
            }
        }

        return agreements;
    }

    private Set<String> extractKeywords(String text) {
        return Arrays.stream(text.toLowerCase().split("\\W+"))
                .filter(word -> word.length() > 3)
                .collect(Collectors.toSet());
    }

    private boolean containsArchitecturalContent(String guidance) {
        String lowerGuidance = guidance.toLowerCase();
        return architecturalKeywords.stream().anyMatch(lowerGuidance::contains);
    }

    private Set<String> extractArchitecturalPatterns(String guidance) {
        Set<String> patterns = new HashSet<>();
        String lowerGuidance = guidance.toLowerCase();

        if (lowerGuidance.contains("microservice"))
            patterns.add("microservice-pattern");
        if (lowerGuidance.contains("domain-driven"))
            patterns.add("ddd-pattern");
        if (lowerGuidance.contains("api gateway"))
            patterns.add("api-gateway-pattern");
        if (lowerGuidance.contains("event-driven"))
            patterns.add("event-driven-pattern");

        return patterns;
    }

    private boolean containsKeywords(String text, Set<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }

    private boolean detectCrossDomainConflicts(List<AgentGuidanceResponse> responses,
            Map<String, List<String>> domainGuidance) {
        // Simple conflict detection: if both security and performance agents provide
        // guidance,
        // check for potential conflicts
        if (domainGuidance.containsKey("security") && domainGuidance.containsKey("performance")) {
            // In a real implementation, this would use more sophisticated conflict
            // detection
            return responses.stream()
                    .anyMatch(r -> r.guidance().toLowerCase().contains("trade-off") ||
                            r.guidance().toLowerCase().contains("conflict"));
        }
        return false;
    }

    private List<String> detectSpecificConflicts(List<AgentGuidanceResponse> responses) {
        List<String> conflicts = new ArrayList<>();

        // Check for contradictory recommendations
        Set<String> allRecommendations = responses.stream()
                .flatMap(r -> r.recommendations().stream())
                .map(String::toLowerCase)
                .collect(Collectors.toSet());

        // Simple contradiction detection
        if (allRecommendations.contains("use synchronous") && allRecommendations.contains("use asynchronous")) {
            conflicts.add("Contradictory recommendations: synchronous vs asynchronous communication");
        }

        if (allRecommendations.contains("use sql") && allRecommendations.contains("use nosql")) {
            conflicts.add("Contradictory recommendations: SQL vs NoSQL database choice");
        }

        return conflicts;
    }

    /**
     * Represents consistency validation for a specific dimension
     */
    public static class ConsistencyDimension {
        private final String name;
        private final double score;
        private final boolean isConsistent;
        private final Map<String, Object> details;

        public ConsistencyDimension(String name, double score, boolean isConsistent,
                Map<String, Object> details) {
            this.name = name;
            this.score = score;
            this.isConsistent = isConsistent;
            this.details = details;
        }

        public String getName() {
            return name;
        }

        public double getScore() {
            return score;
        }

        public boolean isConsistent() {
            return isConsistent;
        }

        public Map<String, Object> getDetails() {
            return details;
        }
    }

    /**
     * Enhanced consistency validation result with multiple dimensions
     */
    public static class EnhancedConsistencyValidationResult {
        private final boolean isConsistent;
        private final double consistencyScore;
        private final List<String> conflicts;
        private final List<String> agreements;
        private final Duration validationTime;
        private final Instant timestamp;
        private final ConsistencyDimension recommendationConsistency;
        private final ConsistencyDimension confidenceConsistency;
        private final ConsistencyDimension semanticConsistency;
        private final ConsistencyDimension architecturalConsistency;
        private final ConsistencyDimension crossDomainConsistency;

        public EnhancedConsistencyValidationResult(boolean isConsistent, double consistencyScore,
                List<String> conflicts, List<String> agreements,
                ConsistencyDimension recommendationConsistency,
                ConsistencyDimension confidenceConsistency,
                ConsistencyDimension semanticConsistency,
                ConsistencyDimension architecturalConsistency,
                ConsistencyDimension crossDomainConsistency,
                Duration validationTime) {
            this.isConsistent = isConsistent;
            this.consistencyScore = consistencyScore;
            this.conflicts = conflicts;
            this.agreements = agreements;
            this.validationTime = validationTime;
            this.timestamp = Instant.now();
            this.recommendationConsistency = recommendationConsistency;
            this.confidenceConsistency = confidenceConsistency;
            this.semanticConsistency = semanticConsistency;
            this.architecturalConsistency = architecturalConsistency;
            this.crossDomainConsistency = crossDomainConsistency;
        }

        public static EnhancedConsistencyValidationResult singleResponse(Duration validationTime) {
            ConsistencyDimension perfect = new ConsistencyDimension("single", 1.0, true, Map.of());
            return new EnhancedConsistencyValidationResult(
                    true, 1.0, List.of(), List.of("Single response - no conflicts possible"),
                    perfect, perfect, perfect, perfect, perfect, validationTime);
        }

        public ConsistencyDimension getRecommendationConsistency() {
            return recommendationConsistency;
        }

        public ConsistencyDimension getConfidenceConsistency() {
            return confidenceConsistency;
        }

        public ConsistencyDimension getSemanticConsistency() {
            return semanticConsistency;
        }

        public ConsistencyDimension getArchitecturalConsistency() {
            return architecturalConsistency;
        }

        public ConsistencyDimension getCrossDomainConsistency() {
            return crossDomainConsistency;
        }

        public boolean hasArchitecturalConflicts() {
            return !architecturalConsistency.isConsistent();
        }

        public boolean hasCrossDomainConflicts() {
            return !crossDomainConsistency.isConsistent();
        }

        // Base properties getters
        public boolean isConsistent() {
            return isConsistent;
        }

        public double consistencyScore() {
            return consistencyScore;
        }

        public List<String> conflicts() {
            return conflicts;
        }

        public List<String> agreements() {
            return agreements;
        }

        public Duration validationTime() {
            return validationTime;
        }

        public Instant timestamp() {
            return timestamp;
        }

        public boolean meetsConsistencyThreshold(double threshold) {
            return consistencyScore >= threshold;
        }
    }
}