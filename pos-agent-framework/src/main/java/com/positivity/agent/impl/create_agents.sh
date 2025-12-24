#!/bin/bash

agents=(
  "ArchitectureAgent:Architecture recommendation"
  "ImplementationAgent:Implementation guidance"
  "TestingAgent:Testing pattern recommendation"
  "DeploymentAgent:Deployment guidance"
  "SecurityAgent:Security recommendation"
  "ObservabilityAgent:Observability guidance"
  "EventDrivenArchitectureAgent:Event-driven pattern"
  "CICDPipelineAgent:CI/CD pipeline guidance"
  "ConfigurationManagementAgent:Configuration management guidance"
  "ResilienceEngineeringAgent:Resilience pattern guidance"
  "DocumentationAgent:Documentation guidance"
  "BusinessDomainAgent:Business domain guidance"
  "PairNavigatorAgent:Code review guidance"
  "IntegrationGatewayAgent:Integration guidance"
  "ArchitecturalGovernanceAgent:Architectural governance guidance"
)

for agent_info in "${agents[@]}"; do
  IFS=':' read -r agent_name output_prefix <<< "$agent_info"
  
  cat > "${agent_name}.java" << JAVA_EOF
package com.positivity.agent.impl;

import com.positivity.agent.core.Agent;
import com.positivity.agent.core.AgentRequest;
import com.positivity.agent.core.AgentResponse;

import java.util.List;

/**
 * ${agent_name}
 */
public class ${agent_name} implements Agent {
    
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
        
        AgentResponse response = new AgentResponse();
        response.setStatus("SUCCESS");
        response.setOutput("${output_prefix}: " + request.getDescription());
        response.setConfidence(0.8);
        response.setRecommendations(List.of("implement pattern", "configure system", "add monitoring"));
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
JAVA_EOF
done
