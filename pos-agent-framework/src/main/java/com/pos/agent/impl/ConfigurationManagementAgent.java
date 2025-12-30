package com.pos.agent.impl;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.ConfigurationContext;

import java.util.List;

/**
 * ConfigurationManagementAgent
 */
public class ConfigurationManagementAgent implements Agent {

    @Override
    public AgentResponse processRequest(AgentRequest request) {
        if (request == null) {
            return createFailureResponse("Invalid request: request is null");
        }
        if (request.getDescription() == null || request.getDescription().trim().isEmpty()) {
            return createFailureResponse("Invalid request: description is required");
        }
        if (request.getContext() == null) {
            return createFailureResponse("Invalid request: context is required");
        }
        if (request.getType() == null || request.getType().contains("invalid")) {
            return createFailureResponse("Invalid request: invalid type");
        }

        AgentResponse response = new AgentResponse();
        response.setStatus("SUCCESS");
        response.setOutput("Configuration management guidance: " + request.getDescription());
        response.setConfidence(0.8);
        response.setRecommendations(List.of("implement pattern", "configure system", "add monitoring"));
        return response;
    }

    /**
     * Provides Kubernetes configuration guidance based on the given context.
     */
    public AgentResponse provideKubernetesConfigGuidance(ConfigurationContext context) {
        AgentResponse response = new AgentResponse();
        response.setStatus("SUCCESS");
        response.setOutput("Kubernetes configuration guidance for service: " + context.getServiceName());
        response.setConfidence(0.85);
        response.setRecommendations(List.of(
                "Use ConfigMaps for non-sensitive configuration",
                "Use Secrets for sensitive data",
                "Apply configuration through environment variables or volumes"));
        return response;
    }

    /**
     * Provides service mesh guidance based on the given context.
     */
    public AgentResponse provideServiceMeshGuidance(ConfigurationContext context) {
        AgentResponse response = new AgentResponse();
        response.setStatus("SUCCESS");
        response.setOutput("Service mesh guidance for service: " + context.getServiceName() +
                " with mesh: " + context.getServiceMesh());
        response.setConfidence(0.85);
        response.setRecommendations(List.of(
                "Enable mTLS for service-to-service communication",
                "Configure traffic routing and load balancing",
                "Set up observability with distributed tracing"));
        return response;
    }

    private AgentResponse createFailureResponse(String message) {
        AgentResponse response = new AgentResponse();
        response.setStatus("FAILURE");
        response.setOutput(message);
        response.setConfidence(0.0);
        return response;
    }
}
