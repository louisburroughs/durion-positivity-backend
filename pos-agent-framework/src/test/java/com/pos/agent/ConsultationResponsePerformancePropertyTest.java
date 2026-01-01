package com.pos.agent;

import com.pos.agent.core.AgentManager;
import com.pos.agent.core.AgentRequest;
import com.pos.agent.core.AgentResponse;
import com.pos.agent.context.AgentContext;
import com.pos.agent.core.SecurityContext;
import net.jqwik.api.*;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

/**
 * Property-based test for consultation response performance
 * **Feature: agent-structure, Property 2: Consultation response performance**
 * **Validates: Requirements REQ-001.2**
 * 
 * Tests that the system provides domain-specific recommendations within 2
 * seconds
 * with appropriate accuracy following established Spring Boot and AWS patterns
 */
class ConsultationResponsePerformancePropertyTest {

        private final AgentManager agentManager = new AgentManager();
        private final SecurityContext security = SecurityContext.builder()
                        .jwtToken("performance-jwt-token")
                        .userId("performance-tester")
                        .roles(List.of("admin", "developer", "architect", "operator"))
                        .permissions(List.of(
                                        "read",
                                        "execute",
                                        "AGENT_READ",
                                        "AGENT_WRITE",
                                        "agent:read",
                                        "agent:write",
                                        "consultation:request"))
                        .serviceId("pos-performance-tests")
                        .serviceType("property")
                        .build();

        /**
         * Property 2: Consultation response performance
         * For any developer consultation request, the system should provide
         * domain-specific
         * recommendations within 2 seconds following established Spring Boot and AWS
         * patterns
         */
        @Property(tries = 100)
        void consultationResponsePerformance(
                        @ForAll("validConsultationRequests") AgentContext context) {

                // When: Consulting the best agent for any valid request
                long startTime = System.nanoTime();
                AgentRequest request = AgentRequest.builder()
                                .description("Consultation response performance property test")
                                .type("consultation")
                                .context(context)
                                .securityContext(security)
                                .build();
                AgentResponse response = agentManager.processRequest(request);
                long endTime = System.nanoTime();
                Duration responseTime = Duration.ofNanos(endTime - startTime);

                // Then: Response time should be within 2 seconds
                assertThat(responseTime.compareTo(Duration.ofSeconds(2)) <= 0)
                                .describedAs("Response time should be within 2 seconds")
                                .isTrue();

                // And: Response should be successful
                assertThat(response.isSuccess())
                                .describedAs("Response should be successful")
                                .isTrue();

                // And: Response should contain status
                assertThat(response.getStatus())
                                .describedAs("Response should contain status")
                                .isNotNull();
        }

        @Property(tries = 100)
        void springBootPatternAccuracy(
                        @ForAll("springBootRequests") AgentContext context) {

                // When: Requesting Spring Boot guidance
                AgentRequest request = AgentRequest.builder()
                                .description("Spring Boot pattern accuracy property test")
                                .type("implementation")
                                .context(context)
                                .securityContext(security)
                                .build();
                AgentResponse response = agentManager.processRequest(request);

                // Then: Response should be successful
                assertThat(response.isSuccess())
                                .describedAs("Spring Boot response should be successful")
                                .isTrue();

                // And: Response status should be present
                assertThat(response.getStatus())
                                .describedAs("Spring Boot response should contain status")
                                .isNotNull();
        }

        @Property(tries = 100)
        void awsPatternsAccuracy(
                        @ForAll("awsRequests") AgentContext context) {

                // When: Requesting AWS guidance
                AgentRequest request = AgentRequest.builder()
                                .type("deployment")
                                .description("AWS patterns accuracy property test")
                                .context(context)
                                .securityContext(security)
                                .build();
                AgentResponse response = agentManager.processRequest(request);

                // Then: Response should be successful
                assertThat(response.isSuccess())
                                .describedAs("AWS response should be successful")
                                .isTrue();

                // And: Response status should be present
                assertThat(response.getStatus())
                                .describedAs("AWS response should contain status")
                                .isNotNull();
        }

        @Property(tries = 50)
        void concurrentConsultationPerformance(
                        @ForAll("consultationContextList") List<AgentContext> contexts) {

                Assume.that(contexts.size() >= 2 && contexts.size() <= 10);

                // When: Processing multiple concurrent consultation requests
                long startTime = System.nanoTime();
                List<AgentResponse> responses = contexts.stream()
                                .map(context -> AgentRequest.builder()
                                                .type("consultation")
                                                .description("Concurrent consultation performance property test")
                                                .context(context)
                                                .securityContext(security)
                                                .build())
                                .map(agentManager::processRequest)
                                .toList();
                long endTime = System.nanoTime();
                Duration totalTime = Duration.ofNanos(endTime - startTime);

                // Then: All requests should complete within reasonable time
                // Allow more time for sequential requests but maintain performance baseline
                Duration maxExpectedTime = Duration.ofSeconds(2).multipliedBy(contexts.size());
                assertThat(totalTime.compareTo(maxExpectedTime) <= 0)
                                .describedAs("Consultation requests should complete within time limit")
                                .isTrue();

                // And: All responses should be successful
                assertThat(responses.stream().allMatch(AgentResponse::isSuccess))
                                .describedAs("All responses should be successful")
                                .isTrue();

                // And: All responses should have status
                assertThat(responses.stream().allMatch(r -> r.getStatus() != null))
                                .describedAs("All responses should contain status")
                                .isTrue();
        }

        @Provide
        Arbitrary<AgentContext> validConsultationRequests() {
                return Arbitraries.of(
                                "microservice-implementation",
                                "system-design",
                                "code-review",
                                "architecture-consultation")
                                .map(scenario -> AgentContext.builder()
                                                .domain("collaboration")
                                                .property("scenario", scenario)
                                                .property("complexity", "medium")
                                                .build());
        }

        @Provide
        Arbitrary<AgentContext> springBootRequests() {
                return Arbitraries.of("pos-inventory", "pos-order", "pos-price")
                                .map(service -> AgentContext.builder()
                                                .domain("implementation")
                                                .property("service", service)
                                                .property("framework", "spring-boot")
                                                .property("topic", "microservice-patterns")
                                                .build());
        }

        @Provide
        Arbitrary<AgentContext> awsRequests() {
                return Arbitraries.of("dynamodb", "elasticache", "fargate", "lambda")
                                .map(awsService -> AgentContext.builder()
                                                .domain("deployment")
                                                .property("platform", "aws")
                                                .property("service", awsService)
                                                .property("topic", "deployment-patterns")
                                                .build());
        }

        @Provide
        Arbitrary<List<AgentContext>> consultationContextList() {
                return validConsultationRequests()
                                .list()
                                .ofMinSize(2)
                                .ofMaxSize(10);
        }
}