package com.pos.agent.impl;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.ImplementationContext;
import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.framework.model.AgentType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ImplementationAgent
 */
public class ImplementationAgent extends AbstractAgent {

    private final Map<String, AgentContext> contextMap = new ConcurrentHashMap<>();

    public ImplementationAgent() {
        super(AgentType.IMPLEMENTATION, List.of(
                "code-implementation",
                "best-practices",
                "refactoring",
                "code-quality",
                "design-patterns"));
    }

    @Override
    public AgentContext getOrCreateContext(String sessionId) {
        return contextMap.computeIfAbsent(sessionId,
                sid -> ImplementationContext.builder().requestId(sessionId).build());
    }

    @Override
    protected AgentResponse doProcessRequest(AgentRequest request) {
        return AgentResponse.builder()
                .status(AgentProcessingState.SUCCESS)
                .output("Implementation guidance: " + request.getAgentContext().getDescription())
                .confidence(0.8)
                .success(true)
                .recommendations(List.of("implement pattern", "configure system", "add monitoring"))
                .build();
    }
}
