package com.pos.agent.collaboration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Session context for tracking progress and decisions.
 */
public class SessionContext {
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
        return Duration.between(lastUpdated, Instant.now()).compareTo(ContextAwareGuidanceManager.SESSION_TIMEOUT) > 0;
    }
}