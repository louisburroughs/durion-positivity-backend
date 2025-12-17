package com.pos.agent.performance;

import com.positivity.agent.controller.AgentConsultationController;
import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
public class LoadTest {

    @Autowired
    private AgentConsultationController agentController;

    private ExecutorService executorService;

    @BeforeEach
    void setUp() {
        executorService = Executors.newFixedThreadPool(100);
    }

    @Test
    void testConcurrentUserLoad() throws Exception {
        // Simulate 100 concurrent developers (performance requirement)
        int concurrentUsers = 100;
        int requestsPerUser = 5;

        List<CompletableFuture<List<AgentResponse>>> userFutures = new ArrayList<>();
        Instant startTime = Instant.now();

        // Create concurrent user sessions
        for (int userId = 0; userId < concurrentUsers; userId++) {
            final int currentUserId = userId;

            CompletableFuture<List<AgentResponse>> userFuture = CompletableFuture.supplyAsync(() -> {
                List<AgentResponse> userResponses = new ArrayList<>();

                // Each user makes multiple requests
                for (int requestId = 0; requestId < requestsPerUser; requestId++) {
                    AgentType agentType = getAgentTypeForUser(currentUserId, requestId);
                    AgentRequest request = createUserRequest(currentUserId, requestId, agentType);

                    AgentResponse response = agentManager.processRequest(request);
                    userResponses.add(response);
                }

                return userResponses;
            }, executorService);

            userFutures.add(userFuture);
        }

        // Wait for all users to complete
        CompletableFuture.allOf(userFutures.toArray(new CompletableFuture[0])).get(60, TimeUnit.SECONDS);

        Instant endTime = Instant.now();
        Duration totalDuration = Duration.between(startTime, endTime);

        // Validate performance requirements
        assertTrue(totalDuration.toSeconds() < 60,
                "100 concurrent users should complete within 60 seconds. Actual: " + totalDuration.toSeconds() + "s");

        // Validate all requests succeeded
        int totalRequests = concurrentUsers * requestsPerUser;
        long successfulRequests = userFutures.stream()
                .mapToLong(future -> {
                    try {
                        List<AgentResponse> responses = future.get();
                        return responses.stream().mapToLong(r -> r.isSuccess() ? 1 : 0).sum();
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .sum();

        assertEquals(totalRequests, successfulRequests, "All concurrent user requests should succeed");

        // Check response time distribution
        List<Long> responseTimes = new ArrayList<>();
        for (CompletableFuture<List<AgentResponse>> userFuture : userFutures) {
            List<AgentResponse> responses = userFuture.get();
            for (AgentResponse response : responses) {
                responseTimes.add(response.getProcessingTimeMs());
            }
        }

        // 95% of responses should be under 500ms
        responseTimes.sort(Long::compareTo);
        int p95Index = (int) (responseTimes.size() * 0.95);
        long p95ResponseTime = responseTimes.get(p95Index);

        assertTrue(p95ResponseTime <= 500,
                "95% of responses should be ≤ 500ms. P95: " + p95ResponseTime + "ms");
    }

    @Test
    void testHighThroughputLoad() throws Exception {
        // Test high throughput scenario
        int totalRequests = 1000;
        AtomicInteger completedRequests = new AtomicInteger(0);
        List<CompletableFuture<AgentResponse>> futures = new ArrayList<>();

        Instant startTime = Instant.now();

        // Submit all requests rapidly
        for (int i = 0; i < totalRequests; i++) {
            AgentType agentType = AgentType.values()[i % AgentType.values().length];
            AgentRequest request = createThroughputRequest(i, agentType);

            CompletableFuture<AgentResponse> future = CompletableFuture.supplyAsync(() -> {
                AgentResponse response = agentManager.processRequest(request);
                completedRequests.incrementAndGet();
                return response;
            }, executorService);

            futures.add(future);
        }

        // Wait for completion
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(120, TimeUnit.SECONDS);

        Instant endTime = Instant.now();
        Duration totalDuration = Duration.between(startTime, endTime);

        // Calculate throughput
        double throughputPerSecond = (double) totalRequests / totalDuration.toSeconds();

        // Should handle at least 10 requests per second
        assertTrue(throughputPerSecond >= 10.0,
                "System should handle ≥ 10 requests/second. Actual: " + throughputPerSecond);

        assertEquals(totalRequests, completedRequests.get(), "All requests should complete");

        // Verify success rate
        long successfulRequests = futures.stream()
                .mapToLong(future -> {
                    try {
                        AgentResponse response = future.get();
                        return response.isSuccess() ? 1 : 0;
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .sum();

        double successRate = (double) successfulRequests / totalRequests * 100;
        assertTrue(successRate >= 99.0, "Success rate should be ≥ 99%. Actual: " + successRate + "%");
    }

    @Test
    void testBurstLoad() throws Exception {
        // Test burst load scenario - sudden spike in requests
        int burstSize = 200;
        List<CompletableFuture<AgentResponse>> burstFutures = new ArrayList<>();

        Instant burstStart = Instant.now();

        // Submit burst of requests simultaneously
        for (int i = 0; i < burstSize; i++) {
            AgentType agentType = AgentType.values()[i % AgentType.values().length];
            AgentRequest request = createBurstRequest(i, agentType);

            CompletableFuture<AgentResponse> future = CompletableFuture.supplyAsync(() -> {
                return agentManager.processRequest(request);
            }, executorService);

            burstFutures.add(future);
        }

        // Wait for burst to complete
        CompletableFuture.allOf(burstFutures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);

        Instant burstEnd = Instant.now();
        Duration burstDuration = Duration.between(burstStart, burstEnd);

        // Burst should be handled within reasonable time
        assertTrue(burstDuration.toSeconds() < 30,
                "Burst load should complete within 30 seconds. Actual: " + burstDuration.toSeconds() + "s");

        // Check that system remains responsive during burst
        long responsesUnder1Second = burstFutures.stream()
                .mapToLong(future -> {
                    try {
                        AgentResponse response = future.get();
                        return response.getProcessingTimeMs() <= 1000 ? 1 : 0;
                    } catch (Exception e) {
                        return 0;
                    }
                })
                .sum();

        double percentageUnder1Second = (double) responsesUnder1Second / burstSize * 100;
        assertTrue(percentageUnder1Second >= 90.0,
                "90% of burst responses should be ≤ 1 second. Actual: " + percentageUnder1Second + "%");
    }

    @Test
    void testSustainedLoad() throws Exception {
        // Test sustained load over time
        int durationSeconds = 60;
        int requestsPerSecond = 5;
        AtomicInteger totalRequests = new AtomicInteger(0);
        AtomicInteger successfulRequests = new AtomicInteger(0);

        Instant testStart = Instant.now();
        Instant testEnd = testStart.plusSeconds(durationSeconds);

        // Submit requests at steady rate
        while (Instant.now().isBefore(testEnd)) {
            List<CompletableFuture<AgentResponse>> batchFutures = new ArrayList<>();

            // Submit batch of requests
            for (int i = 0; i < requestsPerSecond; i++) {
                int requestId = totalRequests.incrementAndGet();
                AgentType agentType = AgentType.values()[requestId % AgentType.values().length];
                AgentRequest request = createSustainedRequest(requestId, agentType);

                CompletableFuture<AgentResponse> future = CompletableFuture.supplyAsync(() -> {
                    AgentResponse response = agentManager.processRequest(request);
                    if (response.isSuccess()) {
                        successfulRequests.incrementAndGet();
                    }
                    return response;
                }, executorService);

                batchFutures.add(future);
            }

            // Wait for batch to complete before next batch
            CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);

            // Small delay to maintain steady rate
            Thread.sleep(1000);
        }

        Instant actualEnd = Instant.now();
        Duration actualDuration = Duration.between(testStart, actualEnd);

        // Validate sustained performance
        double actualThroughput = (double) totalRequests.get() / actualDuration.toSeconds();
        assertTrue(actualThroughput >= (requestsPerSecond * 0.9),
                "Should maintain sustained throughput. Expected: " + requestsPerSecond + "/s, Actual: "
                        + actualThroughput + "/s");

        double successRate = (double) successfulRequests.get() / totalRequests.get() * 100;
        assertTrue(successRate >= 99.0,
                "Success rate should remain high during sustained load. Actual: " + successRate + "%");
    }

    private String getDomainForUser(int userId, int requestId) {
        // Distribute domains based on user patterns
        String[] domains = {
                "architecture", "implementation", "deployment", "testing", "security",
                "observability", "documentation", "business-domain", "integration-gateway",
                "pair-programming-navigator", "event-driven-architecture", "cicd-pipeline",
                "configuration-management", "resilience-engineering"
        };
        int index = (userId * 7 + requestId * 3) % domains.length;
        return domains[index];
    }

    private AgentConsultationRequest createUserRequest(int userId, int requestId, String domain) {
        Map<String, Object> context = new HashMap<>();
        context.put("userId", userId);
        context.put("requestId", requestId);

        String query = "User " + userId + " request " + requestId + " for " + domain;
        return AgentConsultationRequest.create(domain, query, context);
    }

    private AgentRequest createThroughputRequest(int requestId, AgentType agentType) {
        AgentRequest request = new AgentRequest();
        request.setAgentType(agentType);
        request.setRequestId("throughput-" + requestId);
        request.setQuery("Throughput test request " + requestId + " for " + agentType);
        return request;
    }

    private AgentRequest createBurstRequest(int requestId, AgentType agentType) {
        AgentRequest request = new AgentRequest();
        request.setAgentType(agentType);
        request.setRequestId("burst-" + requestId);
        request.setQuery("Burst test request " + requestId + " for " + agentType);
        return request;
    }

    private AgentRequest createSustainedRequest(int requestId, AgentType agentType) {
        AgentRequest request = new AgentRequest();
        request.setAgentType(agentType);
        request.setRequestId("sustained-" + requestId);
        request.setQuery("Sustained test request " + requestId + " for " + agentType);
        return request;
    }
}
