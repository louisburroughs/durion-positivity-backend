package com.pos.agent.impl;

import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentStatus;

import java.util.List;

/**
 * ObservabilityAgent - provides observability guidance and recommendations
 * Extends AbstractAgent for common validation and failure handling
 */
public class ObservabilityAgent extends AbstractAgent {
    
    @Override
    protected AgentResponse doProcessRequest(AgentRequest request) {
        return AgentResponse.builder()
                .status(AgentStatus.SUCCESS)
                .output("Observability guidance: " + request.getDescription())
                .confidence(0.8)
                .success(true)
                .recommendations(List.of("implement pattern", "configure system", "add monitoring"))
                .build();
    }
}
