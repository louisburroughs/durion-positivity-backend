package com.pos.agent.impl;

import com.pos.agent.core.AbstractAgent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.AgentProcessingState;
import com.pos.agent.framework.model.AgentType;

import java.util.List;

/**
 * IntegrationGatewayAgent
 * Extends AbstractAgent for centralized validation and error handling.
 */
public class IntegrationGatewayAgent extends AbstractAgent {

    public IntegrationGatewayAgent() {
        super(AgentType.INTEGRATION_GATEWAY, List.of(
                "api-integration",
                "service-integration",
                "data-mapping",
                "integration-patterns",
                "gateway-design"));
    }

    @Override
    public List<String> getRequiredPermissions() {
        return List.of("SERVICE_READ", "SERVICE_WRITE", "AGENT_READ", "AGENT_WRITE");
    }

    @Override
    protected AgentResponse doProcessRequest(AgentRequest request) {
        return AgentResponse.builder()
                .status(AgentProcessingState.SUCCESS)
                .output("Integration guidance: " + request.getAgentContext().getDescription())
                .confidence(0.8)
                .success(true)
                .recommendations(List.of("implement pattern", "configure system", "add monitoring"))
                .build();
    }
}
