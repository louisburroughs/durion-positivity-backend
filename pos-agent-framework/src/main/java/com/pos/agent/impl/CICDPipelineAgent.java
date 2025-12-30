package com.pos.agent.impl;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.CICDContext;

import java.util.List;

/**
 * CICDPipelineAgent
 */
public class CICDPipelineAgent implements Agent {

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
        response.setOutput("CI/CD pipeline guidance: " + request.getDescription());
        response.setConfidence(0.8);
        response.setRecommendations(List.of("implement pattern", "configure system", "add monitoring"));
        return response;
    }

    /**
     * Provides Kubernetes deployment guidance based on the given context.
     */
    public AgentResponse provideKubernetesDeploymentGuidance(CICDContext context) {
        AgentResponse response = new AgentResponse();
        response.setStatus("SUCCESS");
        response.setOutput("Kubernetes deployment guidance for service: " + context.getServiceName());
        response.setConfidence(0.85);
        response.setRecommendations(List.of(
                "Use rolling deployment strategy for zero-downtime updates",
                "Configure resource requests and limits",
                "Set up liveness and readiness probes"));
        return response;
    }

    /**
     * Provides Helm deployment guidance based on the given context.
     */
    public AgentResponse provideHelmDeploymentGuidance(CICDContext context) {
        AgentResponse response = new AgentResponse();
        response.setStatus("SUCCESS");
        response.setOutput("Helm deployment guidance for service: " + context.getServiceName());
        response.setConfidence(0.85);
        response.setRecommendations(List.of(
                "Use Helm charts for templating Kubernetes manifests",
                "Version your Helm charts with semantic versioning",
                "Store charts in a Helm repository"));
        return response;
    }

    /**
     * Provides canary deployment guidance based on the given context.
     */
    public AgentResponse provideCanaryDeploymentGuidance(CICDContext context) {
        AgentResponse response = new AgentResponse();
        response.setStatus("SUCCESS");
        response.setOutput("Canary deployment guidance for service: " + context.getServiceName());
        response.setConfidence(0.85);
        response.setRecommendations(List.of(
                "Deploy new version to small subset of users",
                "Monitor metrics and error rates",
                "Gradually increase traffic to new version"));
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
