package com.pos.agent.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents the response from an agent after processing a request.
 * Contains the result, metadata, and any additional information.
 */
public class AgentResponse {

    private String requestId;
    private AgentType agentType;
    private boolean success;
    private String result;
    private String errorMessage;
    private long processingTimeMs;
    private Instant timestamp;
    private Map<String, Object> metadata;
    private List<String> recommendations;
    private String agentVersion;

    public AgentResponse() {
        this.timestamp = Instant.now();
        this.metadata = new HashMap<>();
        this.recommendations = new ArrayList<>();
        this.success = true;
    }

    public AgentResponse(String requestId, AgentType agentType) {
        this();
        this.requestId = requestId;
        this.agentType = agentType;
    }

    // Static factory methods
    public static AgentResponse success(String requestId, AgentType agentType, String result) {
        AgentResponse response = new AgentResponse(requestId, agentType);
        response.setSuccess(true);
        response.setResult(result);
        return response;
    }

    public static AgentResponse failure(String requestId, AgentType agentType, String errorMessage) {
        AgentResponse response = new AgentResponse(requestId, agentType);
        response.setSuccess(false);
        response.setErrorMessage(errorMessage);
        return response;
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

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
        if (errorMessage != null) {
            this.success = false;
        }
    }

    public long getProcessingTimeMs() {
        return processingTimeMs;
    }

    public void setProcessingTimeMs(long processingTimeMs) {
        this.processingTimeMs = processingTimeMs;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    public void addMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }

    public List<String> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<String> recommendations) {
        this.recommendations = recommendations != null ? recommendations : new ArrayList<>();
    }

    public void addRecommendation(String recommendation) {
        this.recommendations.add(recommendation);
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    @Override
    public String toString() {
        return String.format("AgentResponse{id='%s', type=%s, success=%s, processingTime=%dms}",
                requestId, agentType, success, processingTimeMs);
    }
}