package com.positivity.agent.core;

import java.util.Map;

/**
 * Request object for agent processing
 * Part of frozen contract specification (REQ-016)
 */
public class AgentRequest {
    private String description;
    private Map<String, Object> context;
    private String type;

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    public void setContext(Map<String, Object> context) {
        this.context = context;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
