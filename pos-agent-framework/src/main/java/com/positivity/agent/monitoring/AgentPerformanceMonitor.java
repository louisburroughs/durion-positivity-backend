package com.positivity.agent.monitoring;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentMetrics;
import com.positivity.agent.AgentPerformanceSpec;
import com.positivity.agent.registry.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Monitors agent performance and triggers alerts when thresholds are exceeded
 */
@Component
public class AgentPerformanceMonitor {

    private static final Logger logger = LoggerFactory.getLogger(AgentPerformanceMonitor.class);

    private final AgentRegistry agentRegistry;
    private final Map<String, PerformanceHistory> performanceHistory = new ConcurrentHashMap<>();

    public AgentPerformanceMonitor(AgentRegistry agentRegistry) {
        this.agentRegistry = agentRegistry;
    }

    /**
     * Monitor agent performance every 30 seconds
     */
    @Scheduled(fixedRate = 30000)
    public void monitorAgentPerformance() {
        List<Agent> agents = agentRegistry.getAllAgents();
        Instant now = Instant.now();

        for (Agent agent : agents) {
            AgentMetrics metrics = agent.getMetrics();
            AgentPerformanceSpec spec = agent.getPerformanceSpec();

            // Update performance history
            PerformanceHistory history = performanceHistory.computeIfAbsent(
                    agent.getId(),
                    k -> new PerformanceHistory());
            history.addMetrics(metrics, now);

            // Check performance against specifications
            checkPerformanceThresholds(agent, metrics, spec);
        }

        // Log overall system performance
        logSystemPerformance(agents);
    }

    private void checkPerformanceThresholds(Agent agent, AgentMetrics metrics, AgentPerformanceSpec spec) {
        String agentId = agent.getId();

        // Check response time threshold
        if (metrics.averageResponseTime().compareTo(spec.responseTime()) > 0) {
            logger.warn("Agent {} exceeds response time threshold: {} > {}",
                    agentId, metrics.averageResponseTime(), spec.responseTime());
        }

        // Check accuracy threshold
        if (metrics.currentAccuracy() < spec.accuracyThreshold()) {
            logger.warn("Agent {} below accuracy threshold: {} < {}",
                    agentId, metrics.currentAccuracy(), spec.accuracyThreshold());
        }

        // Check availability threshold
        if (metrics.availability() < spec.availabilityThreshold()) {
            logger.warn("Agent {} below availability threshold: {} < {}",
                    agentId, metrics.availability(), spec.availabilityThreshold());
        }

        // Check concurrent request limit
        if (metrics.activeRequests() >= spec.maxConcurrentRequests()) {
            logger.warn("Agent {} at maximum capacity: {} requests",
                    agentId, metrics.activeRequests());
        }
    }

    private void logSystemPerformance(List<Agent> agents) {
        if (agents.isEmpty()) {
            return;
        }

        double avgResponseTime = agents.stream()
                .mapToDouble(a -> a.getMetrics().averageResponseTime().toMillis())
                .average()
                .orElse(0.0);

        double avgAccuracy = agents.stream()
                .mapToDouble(a -> a.getMetrics().currentAccuracy())
                .average()
                .orElse(0.0);

        long totalRequests = agents.stream()
                .mapToLong(a -> a.getMetrics().totalRequests())
                .sum();

        int availableAgents = (int) agents.stream()
                .filter(Agent::isAvailable)
                .count();

        logger.debug(
                "System Performance - Agents: {}/{}, Avg Response: {}ms, Avg Accuracy: {:.2f}%, Total Requests: {}",
                availableAgents, agents.size(), avgResponseTime, avgAccuracy * 100, totalRequests);
    }

    /**
     * Get performance history for an agent
     */
    public PerformanceHistory getPerformanceHistory(String agentId) {
        return performanceHistory.get(agentId);
    }

    /**
     * Get system-wide performance summary
     */
    public SystemPerformanceSummary getSystemPerformanceSummary() {
        List<Agent> agents = agentRegistry.getAllAgents();

        if (agents.isEmpty()) {
            return SystemPerformanceSummary.empty();
        }

        double avgResponseTime = agents.stream()
                .mapToDouble(a -> a.getMetrics().averageResponseTime().toMillis())
                .average()
                .orElse(0.0);

        double avgAccuracy = agents.stream()
                .mapToDouble(a -> a.getMetrics().currentAccuracy())
                .average()
                .orElse(0.0);

        double avgAvailability = agents.stream()
                .mapToDouble(a -> a.getMetrics().availability())
                .average()
                .orElse(0.0);

        long totalRequests = agents.stream()
                .mapToLong(a -> a.getMetrics().totalRequests())
                .sum();

        long totalSuccessful = agents.stream()
                .mapToLong(a -> a.getMetrics().successfulRequests())
                .sum();

        int availableAgents = (int) agents.stream()
                .filter(Agent::isAvailable)
                .count();

        return new SystemPerformanceSummary(
                agents.size(),
                availableAgents,
                Duration.ofMillis((long) avgResponseTime),
                avgAccuracy,
                avgAvailability,
                totalRequests,
                totalSuccessful,
                Instant.now());
    }

    /**
     * Performance history for an individual agent
     */
    public static class PerformanceHistory {
        private static final int MAX_HISTORY_SIZE = 100;
        private final List<MetricsSnapshot> snapshots = new java.util.ArrayList<>();

        public synchronized void addMetrics(AgentMetrics metrics, Instant timestamp) {
            snapshots.add(new MetricsSnapshot(metrics, timestamp));

            // Keep only the most recent snapshots
            if (snapshots.size() > MAX_HISTORY_SIZE) {
                snapshots.remove(0);
            }
        }

        public synchronized List<MetricsSnapshot> getSnapshots() {
            return List.copyOf(snapshots);
        }

        public synchronized Duration getAverageResponseTimeOverPeriod(Duration period) {
            Instant cutoff = Instant.now().minus(period);

            double avgMillis = snapshots.stream()
                    .filter(s -> s.timestamp().isAfter(cutoff))
                    .mapToDouble(s -> s.metrics().averageResponseTime().toMillis())
                    .average()
                    .orElse(0.0);

            return Duration.ofMillis((long) avgMillis);
        }
    }

    /**
     * Snapshot of agent metrics at a specific time
     */
    public record MetricsSnapshot(AgentMetrics metrics, Instant timestamp) {
    }

    /**
     * System-wide performance summary
     */
    public record SystemPerformanceSummary(
        int totalAgents,
        int availableAgents,
        Duration averageResponseTime,
        double averageAccuracy,
        double averageAvailability,
        long totalRequests,
        long totalSuccessfulRequests,
        Instant timestamp
    ) {
        public static SystemPerformanceSummary empty() {
            return new SystemPerformanceSummary(
                0, 0, Duration.ZERO, 0.0, 0.0, 0L, 0L, Instant.now()
            );
        }
        
        public double getSuccessRate() {
            return totalRequests > 0 ? (double) totalSuccessfulRequests / totalRequests : 1.0;
        }
        
        public double getAvailabilityPercentage() {
            return totalAgents > 0 ? (double) availableAgents / totalAgents : 1.0;
        }
    }
}