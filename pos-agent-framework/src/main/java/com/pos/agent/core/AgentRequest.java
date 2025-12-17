package com.pos.agent.core;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Represents a request to an agent for processing.
 * Contains all necessary information for the agent to process the request.
 */
public class AgentRequest {

    private String requestId;
    private AgentType agentType;
    private String query;
    private String targetService;
    private Map<String, Object> context;
    private Instant timestamp;
    private String userId;
    private int priority;

    public AgentRequest() {
        this.context = new HashMap<>();
        this.timestamp = Instant.now();
        this.priority = 5; // Default priority (1-10 scale)
    }

    public AgentRequest(String requestId, AgentType agentType, String query) {
        this();
        this.requestId = requestId;
        this.agentType = agentType;
        this.query = query;
    }

    // Getters and Setters
    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public AgentType getAgentType() {
        return agentType;
    }

    public void setAgentType(AgentType agentType) {
        this.agentType = agentType;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public String getTargetService() {
        return targetService;
    }

    public void setTargetService(String targetService) {
        this.targetService = targetService;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context != null ? context : new HashMap<>();
    }

    public void addContextItem(String key, Object value) {
        this.context.put(key, value);
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public int getPriority() {
        return priority;
    }

    public void setPriority(int priority) {
        this.priority = Math.max(1, Math.min(10, priority)); // Clamp to 1-10 range
    }

    @Override
    public String toString() {
        return String.format("AgentRequest{id='%s', type=%s, query='%s', service='%s', priority=%d}",
                requestId, agentType, query, targetService, priority);
    }
}