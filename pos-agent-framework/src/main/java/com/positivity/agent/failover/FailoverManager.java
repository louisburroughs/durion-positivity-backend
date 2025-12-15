package com.positivity.agent.failover;

import com.positivity.agent.Agent;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.AgentHealthStatus;
import com.positivity.agent.config.AgentConfiguration;
import com.positivity.agent.registry.AgentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages failover and recovery mechanisms for agents
 */
@Component
public class FailoverManager {

    private static final Logger logger = LoggerFactory.getLogger(FailoverManager.class);

    private final AgentRegistry agentRegistry;
    private final AgentConfiguration config;
    private final Map<String, FailoverState> failoverStates = new ConcurrentHashMap<>();

    public FailoverManager(AgentRegistry agentRegistry, AgentConfiguration config) {
        this.agentRegistry = agentRegistry;
        this.config = config;
    }

    /**
     * Attempt to provide guidance with automatic failover support
     */
    public CompletableFuture<AgentGuidanceResponse> consultWithFailover(AgentConsultationRequest request) {
        Instant start = Instant.now();

        // Try to find the best primary agent
        Optional<Agent> primaryAgent = agentRegistry.findBestAgent(request);

        if (primaryAgent.isEmpty()) {
            return CompletableFuture.completedFuture(
                    AgentGuidanceResponse.failure(request.requestId(), "failover-manager",
                            "No available agents found", Duration.between(start, Instant.now())));
        }

        Agent agent = primaryAgent.get();
        String agentId = agent.getId();

        return agent.provideGuidance(request)
                .handle((response, throwable) -> {
                    if (throwable != null || !response.isSuccessful()) {
                        logger.warn("Primary agent {} failed for request {}: {}",
                                agentId, request.requestId(),
                                throwable != null ? throwable.getMessage() : response.guidance());

                        // Record failure and attempt failover
                        recordFailure(agentId);
                        return attemptFailover(request, agentId, start);
                    } else {
                        // Record success
                        recordSuccess(agentId);
                        return response;
                    }
                })
                .thenCompose(response -> {
                    if (response instanceof CompletableFuture) {
                        return (CompletableFuture<AgentGuidanceResponse>) response;
                    } else {
                        return CompletableFuture.completedFuture((AgentGuidanceResponse) response);
                    }
                });
    }

    private CompletableFuture<AgentGuidanceResponse> attemptFailover(
            AgentConsultationRequest request, String failedAgentId, Instant start) {

        if (!config.getFailover().isEnableAutomaticFailover()) {
            return CompletableFuture.completedFuture(
                    AgentGuidanceResponse.failure(request.requestId(), failedAgentId,
                            "Automatic failover disabled", Duration.between(start, Instant.now())));
        }

        List<Agent> backupAgents = agentRegistry.getBackupAgents(failedAgentId);

        if (backupAgents.isEmpty()) {
            logger.error("No backup agents available for failed agent {}", failedAgentId);
            return CompletableFuture.completedFuture(
                    AgentGuidanceResponse.failure(request.requestId(), failedAgentId,
                            "No backup agents available", Duration.between(start, Instant.now())));
        }

        // Try backup agents in order
        return tryBackupAgents(request, backupAgents, 0, start);
    }

    private CompletableFuture<AgentGuidanceResponse> tryBackupAgents(
            AgentConsultationRequest request, List<Agent> backupAgents,
            int currentIndex, Instant start) {

        if (currentIndex >= backupAgents.size()) {
            return CompletableFuture.completedFuture(
                    AgentGuidanceResponse.failure(request.requestId(), "failover-manager",
                            "All backup agents failed", Duration.between(start, Instant.now())));
        }

        Agent backupAgent = backupAgents.get(currentIndex);
        logger.info("Attempting failover to backup agent {} for request {}",
                backupAgent.getId(), request.requestId());

        return backupAgent.provideGuidance(request)
                .thenCompose((response) -> {
                    if (response.isSuccessful()) {
                        logger.info("Failover successful using backup agent {} for request {}",
                                backupAgent.getId(), request.requestId());
                        recordSuccess(backupAgent.getId());
                        return CompletableFuture.completedFuture(response);
                    } else {
                        logger.warn("Backup agent {} also failed for request {}",
                                backupAgent.getId(), request.requestId());
                        recordFailure(backupAgent.getId());

                        // Try next backup agent
                        return tryBackupAgents(request, backupAgents, currentIndex + 1, start);
                    }
                })
                .exceptionally(throwable -> {
                    logger.warn("Backup agent {} threw exception for request {}: {}",
                            backupAgent.getId(), request.requestId(), throwable.getMessage());
                    recordFailure(backupAgent.getId());

                    // Try next backup agent
                    return tryBackupAgents(request, backupAgents, currentIndex + 1, start).join();
                });
    }

    /**
     * Monitor agent health and trigger recovery actions
     */
    @Scheduled(fixedRateString = "#{@agentConfiguration.failover.healthCheckInterval.toMillis()}")
    public void monitorAgentHealth() {
        List<Agent> agents = agentRegistry.getAllAgents();

        for (Agent agent : agents) {
            String agentId = agent.getId();
            AgentHealthStatus health = agent.getHealthStatus();
            FailoverState state = failoverStates.get(agentId);

            if (health.state() == AgentHealthStatus.HealthState.UNHEALTHY) {
                if (state == null || state.status() != FailoverStatus.FAILED) {
                    logger.warn("Agent {} is unhealthy: {}", agentId, health.message());
                    markAgentAsFailed(agentId, health.message());
                }
            } else if (health.state() == AgentHealthStatus.HealthState.HEALTHY) {
                if (state != null && state.status() == FailoverStatus.FAILED) {
                    logger.info("Agent {} has recovered", agentId);
                    markAgentAsRecovered(agentId);
                }
            }
        }
    }

    private void recordFailure(String agentId) {
        FailoverState state = failoverStates.computeIfAbsent(agentId, k -> new FailoverState());
        state.recordFailure();

        // Check if agent should be marked as failed
        if (state.getConsecutiveFailures() >= 3) {
            markAgentAsFailed(agentId, "Too many consecutive failures");
        }
    }

    private void recordSuccess(String agentId) {
        FailoverState state = failoverStates.get(agentId);
        if (state != null) {
            state.recordSuccess();
        }
    }

    private void markAgentAsFailed(String agentId, String reason) {
        FailoverState state = failoverStates.computeIfAbsent(agentId, k -> new FailoverState());
        state.markAsFailed(reason);

        logger.error("Agent {} marked as failed: {}", agentId, reason);

        // Schedule recovery attempt
        scheduleRecoveryAttempt(agentId);
    }

    private void markAgentAsRecovered(String agentId) {
        FailoverState state = failoverStates.get(agentId);
        if (state != null) {
            state.markAsRecovered();
        }

        logger.info("Agent {} marked as recovered", agentId);
    }

    private void scheduleRecoveryAttempt(String agentId) {
        // Simple recovery scheduling - in a real implementation,
        // this might use a more sophisticated scheduler
        CompletableFuture.delayedExecutor(
                config.getFailover().getRecoveryTimeout().toMillis(),
                java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> attemptRecovery(agentId));
    }

    private void attemptRecovery(String agentId) {
        Optional<Agent> agent = agentRegistry.getAgent(agentId);
        if (agent.isEmpty()) {
            logger.warn("Cannot attempt recovery for non-existent agent {}", agentId);
            return;
        }

        AgentHealthStatus health = agent.get().getHealthStatus();
        if (health.isAvailable()) {
            logger.info("Agent {} has automatically recovered", agentId);
            markAgentAsRecovered(agentId);
        } else {
            logger.warn("Agent {} recovery attempt failed, will retry later", agentId);
            // Could implement exponential backoff here
        }
    }

    /**
     * Get failover statistics
     */
    public FailoverStatistics getFailoverStatistics() {
        int totalAgents = agentRegistry.getAllAgents().size();
        int failedAgents = (int) failoverStates.values().stream()
                .filter(state -> state.status() == FailoverStatus.FAILED)
                .count();

        long totalFailures = failoverStates.values().stream()
                .mapToLong(FailoverState::getTotalFailures)
                .sum();

        long totalRecoveries = failoverStates.values().stream()
                .mapToLong(FailoverState::getTotalRecoveries)
                .sum();

        return new FailoverStatistics(
                totalAgents,
                failedAgents,
                totalFailures,
                totalRecoveries,
                Instant.now());
    }

    /**
     * Get failover state for a specific agent
     */
    public Optional<FailoverState> getFailoverState(String agentId) {
        return Optional.ofNullable(failoverStates.get(agentId));
    }

    /**
     * Tracks failover state for an individual agent
     */
    public static class FailoverState {
        private FailoverStatus status = FailoverStatus.HEALTHY;
        private int consecutiveFailures = 0;
        private long totalFailures = 0;
        private long totalRecoveries = 0;
        private Instant lastFailure;
        private Instant lastRecovery;
        private String lastFailureReason;

        public synchronized void recordFailure() {
            consecutiveFailures++;
            totalFailures++;
            lastFailure = Instant.now();
        }

        public synchronized void recordSuccess() {
            consecutiveFailures = 0;
        }

        public synchronized void markAsFailed(String reason) {
            status = FailoverStatus.FAILED;
            lastFailureReason = reason;
        }

        public synchronized void markAsRecovered() {
            status = FailoverStatus.HEALTHY;
            consecutiveFailures = 0;
            totalRecoveries++;
            lastRecovery = Instant.now();
            lastFailureReason = null;
        }

        // Getters
        public FailoverStatus status() {
            return status;
        }

        public int getConsecutiveFailures() {
            return consecutiveFailures;
        }

        public long getTotalFailures() {
            return totalFailures;
        }

        public long getTotalRecoveries() {
            return totalRecoveries;
        }

        public Instant getLastFailure() {
            return lastFailure;
        }

        public Instant getLastRecovery() {
            return lastRecovery;
        }

        public String getLastFailureReason() {
            return lastFailureReason;
        }
    }

    public enum FailoverStatus {
        HEALTHY, FAILED, RECOVERING
    }

    /**
     * System-wide failover statistics
     */
    public record FailoverStatistics(
        int totalAgents,
        int failedAgents,
        long totalFailures,
        long totalRecoveries,
        Instant timestamp
    ) {
        public double getFailureRate() {
            return totalAgents > 0 ? (double) failedAgents / totalAgents : 0.0;
        }
        
        public double getRecoveryRate() {
            return totalFailures > 0 ? (double) totalRecoveries / totalFailures : 1.0;
        }
    }
}