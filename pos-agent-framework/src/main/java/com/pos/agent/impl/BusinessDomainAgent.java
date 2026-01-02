package com.pos.agent.impl;

import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.framework.model.AgentType;

import java.util.List;

/**
 * BusinessDomainAgent - provides business domain guidance and recommendations
 * Extends AbstractAgent for common validation and failure handling
 */
public class BusinessDomainAgent extends AbstractAgent {

    public BusinessDomainAgent() {
        super(AgentType.BUSINESS_DOMAIN, List.of(
                "domain-modeling",
                "business-logic",
                "domain-driven-design",
                "ubiquitous-language",
                "bounded-context"));
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
