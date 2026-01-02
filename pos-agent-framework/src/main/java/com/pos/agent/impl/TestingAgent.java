package com.pos.agent.impl;

import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.framework.model.AgentType;
import com.pos.agent.core.AgentProcessingState;

import java.util.List;

/**
 * TestingAgent - provides testing guidance and recommendations
 * Extends AbstractAgent for common validation and failure handling
 */
public class TestingAgent extends AbstractAgent {

    public TestingAgent() {
        super(AgentType.TESTING, List.of(
                "test-strategy",
                "unit-testing",
                "integration-testing",
                "test-automation",
                "tdd-bdd"));
    }

    @Override
    protected AgentResponse doProcessRequest(AgentRequest request) {
        return AgentResponse.builder()
                .status(AgentProcessingState.SUCCESS)
                .output("Testing pattern recommendation: " + request.getAgentContext().getDescription())
                .confidence(0.8)
                .success(true)
                .recommendations(List.of("implement pattern", "configure system", "add monitoring"))
                .build();
    }
}
