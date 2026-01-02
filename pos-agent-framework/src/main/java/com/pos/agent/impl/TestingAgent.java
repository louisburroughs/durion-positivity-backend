package com.pos.agent.impl;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.TestingContext;
import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.framework.model.AgentType;
import com.pos.agent.core.AgentProcessingState;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TestingAgent - provides testing guidance and recommendations
 * Extends AbstractAgent for common validation and failure handling
 */
public class TestingAgent extends AbstractAgent {

    protected static final Map<String, AgentContext> CONTEXT_MAP = new ConcurrentHashMap<>();

    public TestingAgent() {
        super(AgentType.TESTING, List.of(
                "test-strategy",
                "unit-testing",
                "integration-testing",
                "test-automation",
                "tdd-bdd"));
    }

    @Override
    public AgentContext getOrCreateContext(String sessionId) {
        return CONTEXT_MAP.computeIfAbsent(sessionId,
                sid -> TestingContext.builder().requestId(sessionId).build());
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

    @Override
    public void removeContext(String sessionId) {
        CONTEXT_MAP.remove(sessionId);
    }
}
