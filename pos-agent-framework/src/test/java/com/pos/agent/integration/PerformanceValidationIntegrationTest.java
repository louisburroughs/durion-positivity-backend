package com.pos.agent.integration;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.core.SecurityContext;
import com.pos.agent.context.AgentContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance validation tests for the agent framework under production-like
 * load.
 * Tests performance requirements: ≤ 500ms for 95% of requests, ≤ 3 seconds for
 * 99% of requests.
 */
class PerformanceValidationIntegrationTest {

    private AgentManager agentManager;

    private ExecutorService executorService;
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final List<Long> responseTimes = new CopyOnWriteArrayList<>();

    private SecurityContext securityContext;

    @BeforeEach
    void setUp() {
        agentManager = new AgentManager();
        executorService = Executors.newFixedThreadPool(20);
        successCount.set(0);
        failureCount.set(0);
        responseTimes.clear();
        securityContext = SecurityContext.builder()
                .jwtToken("performance-validation-jwt-token")
                .userId("test-user")
                .roles(List.of("USER"))
                .permissions(List.of("AGENT_READ", "AGENT_WRITE"))
                .serviceId("test-service")
                .serviceType("test")
                .build();
    }

    @AfterEach
    void tearDown() {
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    @Test
    void testAgentResponseTimeRequirements() throws InterruptedException {
        int numberOfRequests = 1000;
        CountDownLatch latch = new CountDownLatch(numberOfRequests);

        // Submit concurrent requests
        for (int i = 0; i < numberOfRequests; i++) {
            final int requestIndex = i;
            executorService.submit(() -> {
                try {
                    long startTime = System.currentTimeMillis();

                    String agentType = getAgentTypeForIndex(requestIndex);
                    // Use elevated security context for configuration-management
                    SecurityContext contextToUse = agentType.equals("configuration-management")
                            ? SecurityContext.builder()
                                    .jwtToken("performance-validation-jwt-token")
                                    .userId("test-user")
                                    .roles(List.of("USER", "CONFIG_MANAGER"))
                                    .permissions(
                                            List.of("AGENT_READ", "AGENT_WRITE", "CONFIG_MANAGE", "SECRETS_MANAGE"))
                                    .serviceId("test-service")
                                    .serviceType("test")
                                    .build()
                            : securityContext;

                    AgentRequest request = AgentRequest.builder()
                            .type(agentType)
                            .description("Performance validation test request")
                            .context(createLightweightContext(requestIndex))
                            .securityContext(contextToUse)
                            .build();

                    AgentResponse response = agentManager.processRequest(request);

                    long endTime = System.currentTimeMillis();
                    long responseTime = endTime - startTime;
                    responseTimes.add(responseTime);

                    if (response.isSuccess()) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all requests to complete (max 2 minutes)
        assertTrue(latch.await(120, TimeUnit.SECONDS), "All requests should complete within 2 minutes");

        // Analyze performance metrics
        responseTimes.sort(Long::compareTo);

        long p95ResponseTime = getPercentile(responseTimes, 95);
        long p99ResponseTime = getPercentile(responseTimes, 99);
        long averageResponseTime = responseTimes.stream().mapToLong(Long::longValue).sum() / responseTimes.size();

        System.out.println("Performance Metrics:");
        System.out.println("Total Requests: " + numberOfRequests);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failureCount.get());
        System.out.println("Average Response Time: " + averageResponseTime + "ms");
        System.out.println("95th Percentile: " + p95ResponseTime + "ms");
        System.out.println("99th Percentile: " + p99ResponseTime + "ms");

        // Verify performance requirements
        assertTrue(p95ResponseTime <= 500,
                "95% of requests should complete within 500ms, actual: " + p95ResponseTime + "ms");
        assertTrue(p99ResponseTime <= 3000,
                "99% of requests should complete within 3 seconds, actual: " + p99ResponseTime + "ms");

        // Verify success rate
        double successRate = (double) successCount.get() / numberOfRequests * 100;
        assertTrue(successRate >= 95.0, "Success rate should be at least 95%, actual: " + successRate + "%");
    }

    @Test
    void testConcurrentUserSupport() throws InterruptedException {
        int numberOfUsers = 100;
        int requestsPerUser = 10;
        CountDownLatch latch = new CountDownLatch(numberOfUsers);
        AtomicLong totalResponseTime = new AtomicLong(0);

        // Simulate 100 concurrent users each making 10 requests
        for (int user = 0; user < numberOfUsers; user++) {
            final int userId = user;
            executorService.submit(() -> {
                try {
                    long userStartTime = System.currentTimeMillis();

                    for (int request = 0; request < requestsPerUser; request++) {
                        String agentType = getAgentTypeForIndex(userId + request);
                        // Use elevated security context for configuration-management
                        SecurityContext contextToUse = agentType.equals("configuration-management")
                                ? SecurityContext.builder()
                                        .jwtToken("performance-validation-jwt-token")
                                        .userId("test-user")
                                        .roles(List.of("USER", "CONFIG_MANAGER"))
                                        .permissions(
                                                List.of("AGENT_READ", "AGENT_WRITE", "CONFIG_MANAGE", "SECRETS_MANAGE"))
                                        .serviceId("test-service")
                                        .serviceType("test")
                                        .build()
                                : securityContext;

                        AgentRequest agentRequest = AgentRequest.builder()
                                .type(agentType)
                                .description("Concurrent user performance test request")
                                .context(createUserContext(userId, request))
                                .securityContext(contextToUse)
                                .build();

                        AgentResponse response = agentManager.processRequest(agentRequest);
                        if (response.isSuccess()) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    }

                    long userEndTime = System.currentTimeMillis();
                    totalResponseTime.addAndGet(userEndTime - userStartTime);
                } catch (Exception e) {
                    failureCount.addAndGet(requestsPerUser);
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all users to complete (max 3 minutes)
        assertTrue(latch.await(180, TimeUnit.SECONDS), "All user sessions should complete within 3 minutes");

        long averageUserSessionTime = totalResponseTime.get() / numberOfUsers;
        int totalRequests = numberOfUsers * requestsPerUser;
        double successRate = (double) successCount.get() / totalRequests * 100;

        System.out.println("Concurrent User Test Metrics:");
        System.out.println("Users: " + numberOfUsers);
        System.out.println("Requests per User: " + requestsPerUser);
        System.out.println("Total Requests: " + totalRequests);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failureCount.get());
        System.out.println("Average User Session Time: " + averageUserSessionTime + "ms");
        System.out.println("Success Rate: " + successRate + "%");

        // Verify concurrent user support requirements
        assertTrue(successRate >= 95.0, "Success rate should be at least 95% under concurrent load");
        assertTrue(averageUserSessionTime <= 30000, "Average user session should complete within 30 seconds");
    }

    @Test
    void testMemoryUsageUnderLoad() throws InterruptedException {
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();

        int numberOfRequests = 500;
        CountDownLatch latch = new CountDownLatch(numberOfRequests);

        // Submit memory-intensive requests
        for (int i = 0; i < numberOfRequests; i++) {
            final int requestIndex = i;
            executorService.submit(() -> {
                try {
                    String agentType = getAgentTypeForIndex(requestIndex);
                    // Use elevated security context for configuration-management
                    SecurityContext contextToUse = agentType.equals("configuration-management")
                            ? SecurityContext.builder()
                                    .jwtToken("performance-validation-jwt-token")
                                    .userId("test-user")
                                    .roles(List.of("USER", "CONFIG_MANAGER"))
                                    .permissions(
                                            List.of("AGENT_READ", "AGENT_WRITE", "CONFIG_MANAGE", "SECRETS_MANAGE"))
                                    .serviceId("test-service")
                                    .serviceType("test")
                                    .build()
                            : securityContext;

                    AgentRequest request = AgentRequest.builder()
                            .type(agentType)
                            .description("Memory usage performance test request")
                            .context(createComplexContext(requestIndex))
                            .securityContext(contextToUse)
                            .build();

                    AgentResponse response = agentManager.processRequest(request);

                    if (response.isSuccess()) {
                        successCount.incrementAndGet();
                    } else {
                        failureCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(120, TimeUnit.SECONDS), "All requests should complete within 2 minutes");

        // Force garbage collection and measure memory
        System.gc();
        Thread.sleep(1000);
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;
        long memoryIncreaseMB = memoryIncrease / (1024 * 1024);

        System.out.println("Memory Usage Test Metrics:");
        System.out.println("Initial Memory: " + (initialMemory / (1024 * 1024)) + "MB");
        System.out.println("Final Memory: " + (finalMemory / (1024 * 1024)) + "MB");
        System.out.println("Memory Increase: " + memoryIncreaseMB + "MB");

        // Verify memory usage requirements (should not exceed 2GB increase)
        assertTrue(memoryIncreaseMB <= 2048,
                "Memory increase should not exceed 2GB, actual: " + memoryIncreaseMB + "MB");
    }

    @Test
    void testSystemStabilityUnderSustainedLoad() throws InterruptedException {
        int durationMinutes = 5;
        int requestsPerSecond = 10;
        int totalRequests = durationMinutes * 60 * requestsPerSecond;

        CountDownLatch latch = new CountDownLatch(totalRequests);
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

        // Submit requests at steady rate
        scheduler.scheduleAtFixedRate(() -> {
            for (int i = 0; i < requestsPerSecond && latch.getCount() > 0; i++) {
                executorService.submit(() -> {
                    try {
                        long startTime = System.currentTimeMillis();

                        AgentRequest request = AgentRequest.builder()
                                .type("implementation")
                                .description("Agent test for system stability")
                                .context(AgentContext.builder()
                                        .property("serviceType", "microservice")
                                        .property("framework", "spring-boot")
                                        .build())
                                .securityContext(securityContext)
                                .build();

                        AgentResponse response = agentManager.processRequest(request);

                        long responseTime = System.currentTimeMillis() - startTime;
                        responseTimes.add(responseTime);

                        if (response.isSuccess()) {
                            successCount.incrementAndGet();
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }, 0, 1, TimeUnit.SECONDS);

        // Wait for sustained load test to complete
        assertTrue(latch.await(durationMinutes + 2, TimeUnit.MINUTES),
                "Sustained load test should complete within " + (durationMinutes + 2) + " minutes");

        scheduler.shutdown();

        double successRate = (double) successCount.get() / totalRequests * 100;
        long averageResponseTime = responseTimes.stream().mapToLong(Long::longValue).sum() / responseTimes.size();

        System.out.println("Sustained Load Test Metrics:");
        System.out.println("Duration: " + durationMinutes + " minutes");
        System.out.println("Requests per Second: " + requestsPerSecond);
        System.out.println("Total Requests: " + totalRequests);
        System.out.println("Successful: " + successCount.get());
        System.out.println("Failed: " + failureCount.get());
        System.out.println("Success Rate: " + successRate + "%");
        System.out.println("Average Response Time: " + averageResponseTime + "ms");

        // Verify system stability requirements
        assertTrue(successRate >= 99.0, "Success rate should be at least 99% under sustained load");
        assertTrue(averageResponseTime <= 1000, "Average response time should remain under 1 second");
    }

    private long getPercentile(List<Long> sortedList, int percentile) {
        int index = (int) Math.ceil(sortedList.size() * percentile / 100.0) - 1;
        return sortedList.get(Math.max(0, Math.min(index, sortedList.size() - 1)));
    }

    private String getAgentTypeForIndex(int index) {
        String[] agentTypes = {
                "architecture", "implementation", "deployment", "testing", "security",
                "observability", "documentation", "business-domain", "integration-gateway",
                "pair-programming-navigator", "event-driven-architecture", "cicd-pipeline",
                "configuration-management", "resilience-engineering"
        };
        return agentTypes[index % agentTypes.length];
    }

    private AgentContext createLightweightContext(int index) {
        return AgentContext.builder()
                .agentDomain("implementation")
                .property("serviceType", "microservice")
                .property("framework", "spring-boot")
                .build();
    }

    private AgentContext createUserContext(int userId, int requestIndex) {
        if (requestIndex % 3 == 0) {
            return AgentContext.builder()
                    .agentDomain("implementation")
                    .property("serviceType", "microservice")
                    .property("userContext", "user-" + userId)
                    .build();
        } else if (requestIndex % 3 == 1) {
            return AgentContext.builder()
                    .agentDomain("security")
                    .property("authenticationType", "jwt")
                    .property("authorizationModel", "rbac")
                    .build();
        } else {
            return AgentContext.builder()
                    .agentDomain("testing")
                    .property("testingFramework", "junit5")
                    .property("testTypes", "unit,integration")
                    .build();
        }
    }

    private AgentContext createComplexContext(int index) {
        return AgentContext.builder()
                .agentDomain("architecture")
                .property("serviceType", "microservice")
                .property("contextIdentifier", "complex-domain-" + index)
                .property("scalabilityRequirements", "high")
                .property("integrationPatterns", "event-driven,api-gateway,circuit-breaker")
                .build();
    }
}
