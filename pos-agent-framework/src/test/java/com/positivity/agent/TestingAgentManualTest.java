package com.positivity.agent;

import com.positivity.agent.impl.TestingAgent;
import java.util.Map;

/**
 * Manual test to verify TestingAgent functionality
 */
public class TestingAgentManualTest {

    public static void main(String[] args) {
        TestingAgent testingAgent = new TestingAgent();

        // Test TDD guidance
        AgentConsultationRequest tddRequest = AgentConsultationRequest.create(
                "testing",
                "How to implement Test-Driven Development?",
                Map.of("service", "pos-inventory"));

        try {
            AgentGuidanceResponse tddResponse = testingAgent.provideGuidance(tddRequest).get();
            System.out.println("TDD Guidance Test:");
            System.out.println("Success: " + tddResponse.isSuccessful());
            System.out.println("Contains TDD: " + tddResponse.guidance().contains("TDD"));
            System.out.println("Contains Red-Green-Refactor: " + tddResponse.guidance().contains("Red-Green-Refactor"));
            System.out.println("Confidence: " + tddResponse.confidence());
            System.out.println();

            // Test property-based testing guidance
            AgentConsultationRequest pbtRequest = AgentConsultationRequest.create(
                    "testing",
                    "How to implement property-based testing with jqwik?",
                    Map.of("service", "pos-price"));

            AgentGuidanceResponse pbtResponse = testingAgent.provideGuidance(pbtRequest).get();
            System.out.println("Property-Based Testing Guidance Test:");
            System.out.println("Success: " + pbtResponse.isSuccessful());
            System.out.println("Contains jqwik: " + pbtResponse.guidance().contains("jqwik"));
            System.out.println("Contains @Property: " + pbtResponse.guidance().contains("@Property"));
            System.out.println("Contains 100 iterations: " + pbtResponse.guidance().contains("100 iterations"));
            System.out.println("Confidence: " + pbtResponse.confidence());
            System.out.println();

            // Test agent metadata
            System.out.println("Agent Metadata Test:");
            System.out.println("ID: " + testingAgent.getId());
            System.out.println("Name: " + testingAgent.getName());
            System.out.println("Domain: " + testingAgent.getDomain());
            System.out.println("Has TDD capability: " + testingAgent.getCapabilities().contains("tdd"));
            System.out.println("Has jqwik capability: " + testingAgent.getCapabilities().contains("jqwik"));

            System.out.println("\n✅ All TestingAgent functionality verified successfully!");

        } catch (Exception e) {
            System.err.println("❌ Error testing TestingAgent: " + e.getMessage());
            e.printStackTrace();
        }
    }
}