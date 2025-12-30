package com.pos.agent.impl;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.ResilienceContext;

import java.util.List;

/**
 * ResilienceEngineeringAgent
 */
public class ResilienceEngineeringAgent implements Agent {

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
        response.setOutput("Resilience pattern guidance: " + request.getDescription());
        response.setConfidence(0.8);
        response.setRecommendations(List.of("implement pattern", "configure system", "add monitoring"));
        return response;
    }

    /**
     * Provides Kubernetes health check guidance based on the given context.
     */
    public AgentResponse provideKubernetesHealthCheckGuidance(ResilienceContext context) {
        AgentResponse response = new AgentResponse();
        response.setStatus("SUCCESS");
        response.setOutput("Kubernetes health check guidance for service: " + context.getServiceName());
        response.setConfidence(0.85);
        response.setRecommendations(List.of(
                "Configure liveness probe to detect when container needs restart",
                "Configure readiness probe to control traffic routing",
                "Set appropriate timeout and period values"));
        return response;
    }

    /**
     * Provides Pod Disruption Budget guidance based on the given context.
     */
    public AgentResponse providePodDisruptionBudgetGuidance(ResilienceContext context) {
        AgentResponse response = new AgentResponse();
        response.setStatus("SUCCESS");
        response.setOutput("Pod Disruption Budget guidance for service: " + context.getServiceName());
        response.setConfidence(0.85);
        response.setRecommendations(List.of(
                "Set minAvailable to ensure minimum replicas during disruptions",
                "Use maxUnavailable for controlled rolling updates",
                "Consider cluster maintenance windows"));
        return response;
    }

    /**
     * Provides Horizontal Pod Autoscaler guidance based on the given context.
     */
    public AgentResponse provideHorizontalPodAutoscalerGuidance(ResilienceContext context) {
        AgentResponse response = new AgentResponse();
        response.setStatus("SUCCESS");
        response.setOutput("Horizontal Pod Autoscaler guidance for service: " + context.getServiceName());
        response.setConfidence(0.85);
        response.setRecommendations(List.of(
                "Configure CPU and memory thresholds for scaling",
                "Set appropriate min and max replica counts",
                "Use custom metrics for application-specific scaling"));
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
