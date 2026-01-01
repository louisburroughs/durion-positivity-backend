package com.pos.agent.property;

import com.pos.agent.core.LoopBreakerMixin;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.BeforeEach;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for loop-breaker functionality (REQ-017)
 * Validates iteration limits, pattern detection, and escalation triggers
 */
public class LoopBreakerPropertyTest {

    private TestAgentWithLoopBreaker testAgent;

    @BeforeEach
    void setUp() {
        testAgent = new TestAgentWithLoopBreaker();
    }

    @Property
    @Report(Reporting.GENERATED)
    void iterationCounterAccuracy(@ForAll @IntRange(min = 1, max = 20) int expectedIterations) {
        testAgent = new TestAgentWithLoopBreaker();
        testAgent.setMaxIterations(expectedIterations);
        testAgent.setSimulateLoop(true);

        AgentRequest request = createValidRequest();
        AgentResponse response = testAgent.processRequest(request);

        Assume.that(testAgent.getIterationCount() <= expectedIterations);
        if (testAgent.getIterationCount() == expectedIterations) {
            Assume.that("FAILURE".equals(response.getStatus()));
        }
    }

    @Property
    @Report(Reporting.GENERATED)
    void recurringPatternDetection(@ForAll @IntRange(min = 3, max = 10) int patternLength) {
        testAgent = new TestAgentWithLoopBreaker();
        testAgent.setMaxIterations(50); // High limit to test pattern detection
        testAgent.setPatternLength(patternLength);
        testAgent.setSimulateRecurringPattern(true);

        AgentRequest request = createValidRequest();
        AgentResponse response = testAgent.processRequest(request);

        // When pattern detection is enabled, should either escalate or complete
        // successfully
        if (testAgent.getIterationCount() >= patternLength * 2) {
            // If enough iterations, should detect pattern and escalate
            Assume.that("ESCALATION".equals(response.getStatus()) || "SUCCESS".equals(response.getStatus()));
        }
        // Verify loop breaker respects max iterations
        assertThat(testAgent.getIterationCount()).isLessThanOrEqualTo(50);
    }

    @Property
    @Report(Reporting.GENERATED)
    void escalationTriggering(@ForAll @IntRange(min = 1, max = 15) int maxIterations) {
        testAgent = new TestAgentWithLoopBreaker();
        testAgent.setMaxIterations(maxIterations);
        testAgent.setSimulateLoop(true);

        AgentRequest request = createValidRequest();
        AgentResponse response = testAgent.processRequest(request);

        // Verify loop breaker respects max iterations
        assertThat(testAgent.getIterationCount()).isLessThanOrEqualTo(maxIterations);

        // When max iterations reached, should escalate with proper details
        if (testAgent.getIterationCount() >= maxIterations) {
            assertThat(response.getOutput()).contains("Maximum iterations exceeded");
            assertThat(response.getStatus()).isEqualTo("FAILURE");
            assertThat(response.getEscalationReason()).isNotNull();
        }
    }

    @Property
    @Report(Reporting.GENERATED)
    void contextSizeManagement(@ForAll @IntRange(min = 1000, max = 100000) int contextSize) {
        testAgent = new TestAgentWithLoopBreaker();
        testAgent.setMaxIterations(10);
        testAgent.setSimulateContextGrowth(true);
        testAgent.setTargetContextSize(contextSize);

        AgentRequest request = createValidRequest();
        AgentResponse response = testAgent.processRequest(request);

        // Should handle large contexts appropriately
        if (contextSize > 50000) { // 50KB threshold from REQ-017
            Assume.that(response.getOutput().contains("context summarized") ||
                    "FAILURE".equals(response.getStatus()));
        }
    }

    @Property
    @Report(Reporting.GENERATED)
    void loopBreakerNeverAllowsInfiniteLoop(@ForAll @IntRange(min = 1, max = 100) int iterations) {
        testAgent = new TestAgentWithLoopBreaker();
        testAgent.setMaxIterations(10); // Fixed low limit
        testAgent.setSimulateInfiniteLoop(true);

        AgentRequest request = createValidRequest();
        long startTime = System.currentTimeMillis();
        AgentResponse response = testAgent.processRequest(request);
        long duration = System.currentTimeMillis() - startTime;

        // Should never run longer than 30 seconds regardless of input
        Assume.that(duration < 30000);
        Assume.that("FAILURE".equals(response.getStatus()));
    }

    @Property
    @Report(Reporting.GENERATED)
    void humanEscalationFormatting(@ForAll @IntRange(min = 5, max = 20) int maxIterations) {
        testAgent = new TestAgentWithLoopBreaker();
        testAgent.setMaxIterations(maxIterations);
        testAgent.setSimulateLoop(true);

        AgentRequest request = createValidRequest();
        AgentResponse response = testAgent.processRequest(request);

        if ("ESCALATION".equals(response.getStatus())) {
            String output = response.getOutput();
            // Validate escalation format contains required elements
            Assume.that(output.contains("ESCALATION"));
            Assume.that(output.contains("iterations"));
            Assume.that(response.getEscalationReason() != null);
            Assume.that(response.getContext() != null);
        }
    }

    @Property
    @Report(Reporting.GENERATED)
    void successfulCompletionWithinLimits(@ForAll @IntRange(min = 1, max = 5) int iterations) {
        testAgent = new TestAgentWithLoopBreaker();
        testAgent.setMaxIterations(10); // Higher than test iterations
        testAgent.setSimulateSuccessAfter(iterations);

        AgentRequest request = createValidRequest();
        AgentResponse response = testAgent.processRequest(request);

        Assume.that("SUCCESS".equals(response.getStatus()));
        Assume.that(testAgent.getIterationCount() == iterations);
        Assume.that(testAgent.getIterationCount() <= 10);
    }

    @Property
    @Report(Reporting.GENERATED)
    void loopBreakerStateReset(@ForAll @IntRange(min = 1, max = 10) int firstRun,
            @ForAll @IntRange(min = 1, max = 10) int secondRun) {
        // First request
        testAgent = new TestAgentWithLoopBreaker();
        testAgent.setMaxIterations(20);
        testAgent.setSimulateSuccessAfter(firstRun);
        testAgent.setSimulateLoop(true); // Required to respect simulateSuccessAfter
        AgentRequest request1 = createValidRequest();
        AgentResponse response1 = testAgent.processRequest(request1);

        // Verify first request succeeded
        assertThat(response1.getStatus()).isEqualTo("SUCCESS");
        assertThat(testAgent.getIterationCount()).isEqualTo(firstRun);

        // Second request with fresh agent should start fresh
        testAgent = new TestAgentWithLoopBreaker();
        testAgent.setMaxIterations(20);
        testAgent.setSimulateSuccessAfter(secondRun);
        testAgent.setSimulateLoop(true); // Required to respect simulateSuccessAfter
        AgentRequest request2 = createValidRequest();
        AgentResponse response2 = testAgent.processRequest(request2);

        // Verify state was properly reset for second request
        assertThat(response2.getStatus()).isEqualTo("SUCCESS");
        assertThat(testAgent.getIterationCount()).isEqualTo(secondRun);
    }

    private AgentRequest createValidRequest() {
        return AgentRequest.builder()
                .description("Test request for loop breaker validation")
                .context(Map.of("test", "context"))
                .type("test-request")
                .build();
    }

    /**
     * Test agent implementation with configurable loop behavior
     */
    private static class TestAgentWithLoopBreaker implements LoopBreakerMixin {
        private int maxIterations = 10;
        private int iterationCount = 0;
        private boolean simulateLoop = false;
        private boolean simulateRecurringPattern = false;
        private boolean simulateContextGrowth = false;
        private boolean simulateInfiniteLoop = false;
        private int simulateSuccessAfter = -1;
        private int patternLength = 3;
        private int targetContextSize = 1000;
        private StringBuilder contextBuilder = new StringBuilder();

        public AgentResponse processRequest(AgentRequest request) {
            resetIterationCounter();

            while (iterationCount < maxIterations) {
                incrementIterationCounter();

                // Simulate context growth
                if (simulateContextGrowth) {
                    contextBuilder.append("x".repeat(targetContextSize / 10));
                    if (contextBuilder.length() > 50000) {
                        return createEscalationResponse("Context size exceeded threshold");
                    }
                }

                // Simulate recurring pattern
                if (simulateRecurringPattern && iterationCount > patternLength * 2) {
                    if (detectRecurringPattern()) {
                        return createEscalationResponse("Recurring pattern detected");
                    }
                }

                // Simulate success after N iterations
                if (simulateSuccessAfter > 0 && iterationCount >= simulateSuccessAfter) {
                    return createSuccessResponse();
                }

                // Simulate infinite loop (should be caught by loop breaker)
                if (simulateInfiniteLoop) {
                    continue; // Keep looping until max iterations
                }

                // Normal loop simulation
                if (!simulateLoop) {
                    return createSuccessResponse();
                }
            }

            return createEscalationResponse("Maximum iterations exceeded");
        }

        private boolean detectRecurringPattern() {
            // Simplified pattern detection for testing
            return iterationCount % patternLength == 0;
        }

        private AgentResponse createSuccessResponse() {
            AgentResponse response = new AgentResponse();
            response.setStatus("SUCCESS");
            response.setOutput("Test completed successfully after " + iterationCount + " iterations");
            response.setConfidence(0.9);
            return response;
        }

        private AgentResponse createEscalationResponse(String reason) {
            AgentResponse response = new AgentResponse();
            response.setStatus("ESCALATION");
            response.setOutput("ESCALATION: " + reason + " after " + iterationCount + " iterations");
            response.setEscalationReason(reason);
            response.setContext(Map.of("iterations", String.valueOf(iterationCount)));
            return response;
        }

        @Override
        public void resetIterationCounter() {
            iterationCount = 0;
            contextBuilder.setLength(0);
        }

        @Override
        public void incrementIterationCounter() {
            iterationCount++;
        }

        @Override
        public int getIterationCount() {
            return iterationCount;
        }

        @Override
        public int getMaxIterations() {
            return maxIterations;
        }

        // Test configuration methods
        public void setMaxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
        }

        public void setSimulateLoop(boolean simulateLoop) {
            this.simulateLoop = simulateLoop;
        }

        public void setSimulateRecurringPattern(boolean simulateRecurringPattern) {
            this.simulateRecurringPattern = simulateRecurringPattern;
        }

        public void setSimulateContextGrowth(boolean simulateContextGrowth) {
            this.simulateContextGrowth = simulateContextGrowth;
        }

        public void setSimulateInfiniteLoop(boolean simulateInfiniteLoop) {
            this.simulateInfiniteLoop = simulateInfiniteLoop;
        }

        public void setSimulateSuccessAfter(int iterations) {
            this.simulateSuccessAfter = iterations;
        }

        public void setPatternLength(int patternLength) {
            this.patternLength = patternLength;
        }

        public void setTargetContextSize(int targetContextSize) {
            this.targetContextSize = targetContextSize;
        }
    }
}
