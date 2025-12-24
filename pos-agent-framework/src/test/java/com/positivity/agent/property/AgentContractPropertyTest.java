package com.positivity.agent.property;

import com.positivity.agent.core.Agent;
import com.positivity.agent.core.AgentRequest;
import com.positivity.agent.core.AgentResponse;
import com.positivity.agent.impl.*;
import net.jqwik.api.*;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.BeforeEach;

import java.util.List;
import java.util.Map;

/**
 * Property-based tests for agent frozen contracts (REQ-016)
 * Validates contract enforcement, input validation, and output format consistency
 */
public class AgentContractPropertyTest {

    private List<Agent> getAgents() {
        return List.of(
            new ArchitectureAgent(),
            new ImplementationAgent(),
            new TestingAgent(),
            new DeploymentAgent(),
            new SecurityAgent(),
            new ObservabilityAgent(),
            new EventDrivenArchitectureAgent(),
            new CICDPipelineAgent(),
            new ConfigurationManagementAgent(),
            new ResilienceEngineeringAgent(),
            new DocumentationAgent(),
            new BusinessDomainAgent(),
            new PairNavigatorAgent(),
            new IntegrationGatewayAgent(),
            new ArchitecturalGovernanceAgent()
        );
    }

    @Property
    @Report(Reporting.GENERATED)
    void agentResponsesAreNeverNull(@ForAll("validAgentRequests") AgentRequest request) {
        for (Agent agent : getAgents()) {
            AgentResponse response = agent.processRequest(request);
            assert response != null : "Agent response should never be null";
        }
    }

    @Property
    @Report(Reporting.GENERATED)
    void agentResponsesHaveValidStatus(@ForAll("validAgentRequests") AgentRequest request) {
        for (Agent agent : getAgents()) {
            AgentResponse response = agent.processRequest(request);
            assert response.getStatus() != null : "Response status should not be null";
            assert List.of("SUCCESS", "FAILURE", "ESCALATION", "INSUFFICIENT_CONTEXT")
                .contains(response.getStatus()) : "Status should be valid: " + response.getStatus();
        }
    }

    @Property
    @Report(Reporting.GENERATED)
    void agentResponsesHaveNonEmptyOutput(@ForAll("validAgentRequests") AgentRequest request) {
        for (Agent agent : getAgents()) {
            AgentResponse response = agent.processRequest(request);
            if ("SUCCESS".equals(response.getStatus())) {
                assert response.getOutput() != null : "Successful response should have output";
                assert !response.getOutput().trim().isEmpty() : "Successful response output should not be empty";
            }
        }
    }

    @Property
    @Report(Reporting.GENERATED)
    void agentInputValidationRejectsInvalidRequests(@ForAll("invalidAgentRequests") AgentRequest request) {
        for (Agent agent : getAgents()) {
            AgentResponse response = agent.processRequest(request);
            assert "FAILURE".equals(response.getStatus()) || 
                   "INSUFFICIENT_CONTEXT".equals(response.getStatus()) : 
                   "Invalid requests should result in FAILURE or INSUFFICIENT_CONTEXT, got: " + response.getStatus();
        }
    }

    @Property
    @Report(Reporting.GENERATED)
    void agentOutputFormatIsConsistent(@ForAll("validAgentRequests") AgentRequest request) {
        for (Agent agent : getAgents()) {
            AgentResponse response = agent.processRequest(request);
            if ("SUCCESS".equals(response.getStatus())) {
                String output = response.getOutput();
                assert output.length() > 10 : "Output should be meaningful, got length: " + output.length();
                assert output.contains("guidance") || 
                       output.contains("recommendation") || 
                       output.contains("pattern") : "Output should contain guidance keywords";
            }
        }
    }

    @Property
    @Report(Reporting.GENERATED)
    void agentConfidenceScoreIsValid(@ForAll("validAgentRequests") AgentRequest request) {
        for (Agent agent : getAgents()) {
            AgentResponse response = agent.processRequest(request);
            double confidence = response.getConfidence();
            assert confidence >= 0.0 && confidence <= 1.0 : 
                "Confidence should be between 0.0 and 1.0, got: " + confidence;
        }
    }

    @Property
    @Report(Reporting.GENERATED)
    void agentRecommendationsAreActionable(@ForAll("validAgentRequests") AgentRequest request) {
        for (Agent agent : getAgents()) {
            AgentResponse response = agent.processRequest(request);
            if ("SUCCESS".equals(response.getStatus()) && response.getRecommendations() != null) {
                List<String> recommendations = response.getRecommendations();
                for (String recommendation : recommendations) {
                    assert recommendation != null : "Recommendation should not be null";
                    assert recommendation.length() > 5 : "Recommendation should be meaningful";
                    // Should contain actionable verbs - relaxed check
                    assert recommendation.toLowerCase().matches(".*\\b(implement|create|configure|add|update|use|apply|enable|setup|pattern|guidance|design|build|deploy)\\b.*") :
                        "Recommendation should contain actionable content: " + recommendation;
                }
            }
        }
    }

    @Property
    @Report(Reporting.GENERATED)
    void agentProcessingTimeIsReasonable(@ForAll("validAgentRequests") AgentRequest request) {
        for (Agent agent : getAgents()) {
            long startTime = System.currentTimeMillis();
            AgentResponse response = agent.processRequest(request);
            long processingTime = System.currentTimeMillis() - startTime;
            
            assert processingTime < 5000 : "Processing should complete within 5 seconds, took: " + processingTime + "ms";
        }
    }

    @Provide
    Arbitrary<AgentRequest> validAgentRequests() {
        return Combinators.combine(
            Arbitraries.strings().withCharRange('a', 'z').ofMinLength(10).ofMaxLength(100),
            Arbitraries.maps(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(3).ofMaxLength(20),
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(5).ofMaxLength(50)
            ).ofMinSize(1).ofMaxSize(5)
        ).as((description, context) -> {
            AgentRequest request = new AgentRequest();
            request.setDescription(description);
            // Convert Map<String, String> to Map<String, Object>
            Map<String, Object> objectContext = context.entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                    Map.Entry::getKey,
                    e -> (Object) e.getValue()
                ));
            request.setContext(objectContext);
            request.setType("architecture-guidance");
            return request;
        });
    }

    @Provide
    Arbitrary<AgentRequest> invalidAgentRequests() {
        return Arbitraries.oneOf(
            // Null description
            Arbitraries.just(createRequestWithNullDescription()),
            // Empty description
            Arbitraries.just(createRequestWithEmptyDescription()),
            // Null context
            Arbitraries.just(createRequestWithNullContext()),
            // Invalid type
            Arbitraries.just(createRequestWithInvalidType())
        );
    }

    private AgentRequest createRequestWithNullDescription() {
        AgentRequest request = new AgentRequest();
        request.setDescription(null);
        request.setContext(Map.of("key", "value"));
        request.setType("architecture-guidance");
        return request;
    }

    private AgentRequest createRequestWithEmptyDescription() {
        AgentRequest request = new AgentRequest();
        request.setDescription("");
        request.setContext(Map.of("key", "value"));
        request.setType("architecture-guidance");
        return request;
    }

    private AgentRequest createRequestWithNullContext() {
        AgentRequest request = new AgentRequest();
        request.setDescription("Valid description");
        request.setContext(null);
        request.setType("architecture-guidance");
        return request;
    }

    private AgentRequest createRequestWithInvalidType() {
        AgentRequest request = new AgentRequest();
        request.setDescription("Valid description");
        request.setContext(Map.of("key", "value"));
        request.setType("invalid-type-12345");
        return request;
    }
}
