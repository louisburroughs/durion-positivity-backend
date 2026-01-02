package com.pos.agent.impl;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.ArchitectureContext;
import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.framework.model.AgentType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ArchitecturalGovernanceAgent
 */
public class ArchitecturalGovernanceAgent extends AbstractAgent {

    private final Map<String, AgentContext> contextMap = new ConcurrentHashMap<>();

    public ArchitecturalGovernanceAgent() {
        super(AgentType.ARCHITECTURE, List.of(
                "governance-policies",
                "architectural-standards",
                "compliance-review",
                "pattern-enforcement",
                "architectural-debt-management"));
    }

    @Override
    public AgentContext getOrCreateContext(String sessionId) {
        return contextMap.computeIfAbsent(sessionId,
                sid -> ArchitectureContext.builder().requestId(sessionId).build());
    }

    @Override
    protected AgentResponse doProcessRequest(AgentRequest request) {
        return AgentResponse.builder()
                .status(AgentProcessingState.SUCCESS)
                .output("Architectural governance guidance: " + request.getAgentContext().getDescription())
                .confidence(0.8)
                .success(true)
                .recommendations(List.of("implement pattern", "configure system", "add monitoring"))
                .build();
    }
}
