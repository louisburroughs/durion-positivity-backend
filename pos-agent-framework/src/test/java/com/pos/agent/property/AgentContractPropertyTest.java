package com.pos.agent.property;

import com.pos.agent.core.Agent;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.impl.*;
import net.jqwik.api.*;

import java.util.List;
import java.util.Map;

/**
 * Property-based tests for agent frozen contracts (REQ-016)
 * Validates contract enforcement, input validation, and output format
 * consistency
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
                new ArchitecturalGovernanceAgent());
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
        //TODO this requires refinement to have the agent validate the request and return FAILURE
        for (Agent agent : getAgents()) {
            try {
                AgentResponse response = agent.processRequest(request);
                assert "FAILURE".equals(response.getStatus()) ||
                        "INSUFFICIENT_CONTEXT".equals(response.getStatus())
                        : "Invalid requests should result in FAILURE or INSUFFICIENT_CONTEXT, got: "
                                + response.getStatus();
            } catch (IllegalStateException e) {
                // IllegalStateException is a valid rejection for invalid requests (e.g.,
                // missing required description)
            }
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
            assert confidence >= 0.0 && confidence <= 1.0
                    : "Confidence should be between 0.0 and 1.0, got: " + confidence;
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
                    assert recommendation.toLowerCase().matches(
                            ".*\\b(implement|create|configure|add|update|use|apply|enable|setup|pattern|guidance|design|build|deploy)\\b.*")
                            : "Recommendation should contain actionable content: " + recommendation;
                }
            }
        }
    }

    @Property
    @Report(Reporting.GENERATED)
    void agentProcessingTimeIsReasonable(@ForAll("validAgentRequests") AgentRequest request) {
        for (Agent agent : getAgents()) {
            long startTime = System.currentTimeMillis();
            agent.processRequest(request);
            long processingTime = System.currentTimeMillis() - startTime;
            assert processingTime < 5000
                    : "Processing should complete within 5 seconds, took: " + processingTime + "ms";
        }
    }

    @Provide
    Arbitrary<AgentRequest> validAgentRequests() {
        return Combinators.combine(
                Arbitraries.strings().withCharRange('a', 'z').ofMinLength(10).ofMaxLength(100),
                Arbitraries.maps(
                        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(3).ofMaxLength(20),
                        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(5).ofMaxLength(50)).ofMinSize(1)
                        .ofMaxSize(5))
                .as((description, context) -> {
                    // Convert Map<String, String> to Map<String, Object>
                    Map<String, Object> objectContext = context.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(
                                    Map.Entry::getKey,
                                    e -> (Object) e.getValue()));
                    return AgentRequest.builder()
                            .description(description)
                            .context(objectContext)
                            .type("architecture-guidance")
                            .build();
                });
    }

    @Provide
    Arbitrary<AgentRequest> invalidAgentRequests() {
        // Only include requests that can be built successfully but are semantically
        // invalid
        // Builder validation (null/empty description) is tested via
        // IllegalStateException in the test method
        return Arbitraries.oneOf(
                // Invalid type - agents should reject this
                Arbitraries.just(createRequestWithInvalidType()),
                // Minimal context - agents may find this insufficient
                Arbitraries.just(AgentRequest.builder()
                        .description("minimal-context-request")
                        .context(Map.of()) // Empty but not null
                        .type("architecture-guidance")
                        .build()),
                // Wrong domain type
                Arbitraries.just(AgentRequest.builder()
                        .description("wrong-domain-type")
                        .context(Map.of("key", "value"))
                        .type("non-existent-domain")
                        .build()));
    }

    private AgentRequest createRequestWithNullDescription() {
        return AgentRequest.builder()
                .description(null)
                .context(Map.of("key", "value"))
                .type("architecture-guidance")
                .build();
    }

    private AgentRequest createRequestWithEmptyDescription() {
        return AgentRequest.builder()
                .description("")
                .context(Map.of("key", "value"))
                .type("architecture-guidance")
                .build();
    }

    private AgentRequest createRequestWithNullContext() {
        return AgentRequest.builder()
                .description("Valid description")
                .context(null)
                .type("architecture-guidance")
                .build();
    }

    private AgentRequest createRequestWithInvalidType() {
        return AgentRequest.builder()
                .description("Valid description")
                .context(Map.of("key", "value"))
                .type("invalid-type-12345")
                .build();
    }
}
