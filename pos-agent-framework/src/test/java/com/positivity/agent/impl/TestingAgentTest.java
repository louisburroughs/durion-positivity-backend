package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for TestingAgent implementation
 */
class TestingAgentTest {

    private TestingAgent testingAgent;

    @BeforeEach
    void setUp() {
        testingAgent = new TestingAgent();
    }

    @Test
    void shouldProvideUnitTestingGuidance() throws ExecutionException, InterruptedException {
        // Given
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "testing",
                "How to implement unit tests with JUnit 5?",
                Map.of("service", "pos-catalog"));

        // When
        AgentGuidanceResponse response = testingAgent.provideGuidance(request).get();

        // Then
        assertThat(response.isSuccessful()).isTrue();
        assertThat(response.guidance()).contains("JUnit 5");
        assertThat(response.guidance()).contains("Mockito");
        assertThat(response.guidance()).contains("AAA pattern");
        assertThat(response.confidence()).isGreaterThan(0.9);
    }

    @Test
    void shouldProvideTDDGuidance() throws ExecutionException, InterruptedException {
        // Given
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "testing",
                "How to implement Test-Driven Development?",
                Map.of("service", "pos-inventory"));

        // When
        AgentGuidanceResponse response = testingAgent.provideGuidance(request).get();

        // Then
        assertThat(response.isSuccessful()).isTrue();
        assertThat(response.guidance()).contains("Red-Green-Refactor");
        assertThat(response.guidance()).contains("Write failing test");
        assertThat(response.guidance()).contains("TDD");
        assertThat(response.confidence()).isGreaterThan(0.9);
    }

    @Test
    void shouldProvidePropertyBasedTestingGuidance() throws ExecutionException, InterruptedException {
        // Given
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "testing",
                "How to implement property-based testing with jqwik?",
                Map.of("service", "pos-price"));

        // When
        AgentGuidanceResponse response = testingAgent.provideGuidance(request).get();

        // Then
        assertThat(response.isSuccessful()).isTrue();
        assertThat(response.guidance()).contains("jqwik");
        assertThat(response.guidance()).contains("@Property");
        assertThat(response.guidance()).contains("@ForAll");
        assertThat(response.guidance()).contains("100 iterations");
        assertThat(response.confidence()).isGreaterThan(0.9);
    }

    @Test
    void shouldHaveCorrectAgentMetadata() {
        // Then
        assertThat(testingAgent.getId()).isEqualTo("testing-agent");
        assertThat(testingAgent.getName()).isEqualTo("Testing Agent");
        assertThat(testingAgent.getDomain()).isEqualTo("testing");
        assertThat(testingAgent.getCapabilities()).contains(
                "unit-testing", "integration-testing", "contract-testing",
                "tdd", "property-based-testing", "jqwik", "junit5", "mockito", "pact");
    }
}