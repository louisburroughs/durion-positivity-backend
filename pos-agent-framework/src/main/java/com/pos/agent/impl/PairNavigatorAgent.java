package com.pos.agent.impl;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.EventDrivenContext;
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
 * PairNavigatorAgent
 */
public class PairNavigatorAgent extends AbstractAgent {

    protected static final Map<String, AgentContext> CONTEXT_MAP = new ConcurrentHashMap<>();

    public PairNavigatorAgent() {
        super(AgentType.PAIR_PROGRAMMING, List.of(
                "pair-programming",
                "code-review",
                "collaborative-development",
                "knowledge-sharing",
                "code-quality-feedback"));
    }

     @Override
    public AgentContext getOrCreateContext(String sessionId) {
        return CONTEXT_MAP.computeIfAbsent(sessionId,
                sid -> ImplementationContext.builder().requestId(sessionId).build());
    }

    @Override
    protected AgentResponse doProcessRequest(AgentRequest request) {
        return AgentResponse.builder()
                .status(AgentProcessingState.SUCCESS)
                .output("Code review guidance: " + request.getAgentContext().getDescription())
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
