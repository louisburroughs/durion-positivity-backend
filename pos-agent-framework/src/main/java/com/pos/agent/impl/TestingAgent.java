package com.pos.agent.impl;

import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentStatus;

import java.util.List;

/**
 * TestingAgent - Provides testing guidance and recommendations.
 * Extends AbstractAgent to leverage centralized validation.
 */
public class TestingAgent extends AbstractAgent {
    
    @Override
    protected AgentResponse handle(AgentRequest request) {
        return AgentResponse.builder()
                .status(AgentStatus.SUCCESS)
                .output("Testing pattern recommendation: " + request.getDescription())
                .confidence(0.8)
                .recommendations(List.of("implement pattern", "configure system", "add monitoring"))
                .build();
    }
}
