package com.pos.agent.impl;

import com.pos.agent.context.AgentContext;
import com.pos.agent.context.BusinessContext;
import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.framework.model.AgentType;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BusinessDomainAgent - provides business domain guidance and recommendations
 * Extends AbstractAgent for common validation and failure handling
 */
public class BusinessDomainAgent extends AbstractAgent {

    private final Map<String, AgentContext> contextMap = new ConcurrentHashMap<>();

    public BusinessDomainAgent() {
        super(AgentType.BUSINESS_DOMAIN, List.of(
                "domain-modeling",
                "business-logic",
                "domain-driven-design",
                "ubiquitous-language",
                "bounded-context"));
    }

    @Override
    public AgentContext getOrCreateContext(String sessionId) {
        return contextMap.computeIfAbsent(sessionId,
                sid -> BusinessContext.builder().requestId(sessionId).build());
    }

    @Override
    protected AgentResponse doProcessRequest(AgentRequest request) {
        return AgentResponse.builder()
                .status(AgentProcessingState.SUCCESS)
                .output("Business domain guidance: " + request.getAgentContext().getDescription())
                .confidence(0.8)
                .success(true)
                .recommendations(List.of("implement pattern", "configure system", "add monitoring"))
                .build();
    }
}
