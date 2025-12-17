package com.positivity.agent.impl;

import com.positivity.agent.AgentConsultationRequest;
import com.positivity.agent.AgentGuidanceResponse;
import com.positivity.agent.AgentPerformanceSpec;
import com.positivity.agent.BaseAgent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Testing Agent - Comprehensive testing strategies and quality assurance
 * Specializes in unit testing, integration testing, and contract testing
 */
@Component
public class TestingAgent extends BaseAgent {

    public TestingAgent() {
        super(
                "testing-agent",
                "Testing Agent",
                "testing",
                Set.of("unit-testing", "integration-testing", "contract-testing", "quality-assurance",
                        "test-automation", "tdd", "test-driven-development", "property-based-testing",
                        "jqwik", "junit5", "mockito", "pact", "spring-boot-test"),
                Set.of("implementation-agent"), // Depends on implementation details
                AgentPerformanceSpec.defaultSpec());
    }

    @Override
    protected AgentGuidanceResponse processGuidanceRequest(AgentConsultationRequest request) {
        String guidance = generateTestingGuidance(request);
        List<String> recommendations = generateTestingRecommendations(request);

        return AgentGuidanceResponse.success(
                request.requestId(),
                getId(),
                guidance,
                0.96, // Very high confidence for testing guidance
                recommendations,
                Duration.ofMillis(120));
    }

    private String generateTestingGuidance(AgentConsultationRequest request) {
        String baseGuidance = "Testing Guidance for " + request.domain() + ":\n\n";

        if (request.query().toLowerCase().contains("unit test") || request.query().toLowerCase().contains("junit")) {
            return baseGuidance +
                    "Unit Testing Implementation:\n" +
                    "- Use JUnit 5 with proper test structure and naming\n" +
                    "- Implement test isolation with @MockBean and @Mock\n" +
                    "- Use Mockito for mocking dependencies\n" +
                    "- Follow AAA pattern: Arrange, Act, Assert\n" +
                    "- Test edge cases and error conditions\n" +
                    "- Aim for high code coverage (>80%) with meaningful tests\n" +
                    "- Use parameterized tests for multiple scenarios\n" +
                    "- Implement proper test data builders and fixtures";
        }

        if (request.query().toLowerCase().contains("integration test")
                || request.query().toLowerCase().contains("spring boot test")) {
            return baseGuidance +
                    "Integration Testing Implementation:\n" +
                    "- Use @SpringBootTest for full application context testing\n" +
                    "- Use @WebMvcTest for web layer testing\n" +
                    "- Use @DataJpaTest for repository layer testing\n" +
                    "- Implement proper test containers for database testing\n" +
                    "- Use @TestPropertySource for test-specific configuration\n" +
                    "- Test actual HTTP endpoints with TestRestTemplate\n" +
                    "- Implement proper test data setup and cleanup\n" +
                    "- Test cross-cutting concerns like security and transactions";
        }

        if (request.query().toLowerCase().contains("contract test") || request.query().toLowerCase().contains("pact")) {
            return baseGuidance +
                    "Contract Testing Implementation:\n" +
                    "- Use Pact for consumer-driven contract testing\n" +
                    "- Define clear API contracts between services\n" +
                    "- Implement provider verification tests\n" +
                    "- Use contract testing for microservice integration\n" +
                    "- Maintain contract compatibility during evolution\n" +
                    "- Integrate contract tests into CI/CD pipeline\n" +
                    "- Use schema validation for API contracts\n" +
                    "- Document API contracts with OpenAPI specifications";
        }

        if (request.query().toLowerCase().contains("data access")
                || request.query().toLowerCase().contains("database test")) {
            return baseGuidance +
                    "Data Access Testing for PostgreSQL and ElastiCache:\n" +
                    "- Use @DataJpaTest for repository testing with PostgreSQL\n" +
                    "- Test with actual database using TestContainers PostgreSQL\n" +
                    "- Test both PostgreSQL and ElastiCache interactions\n" +
                    "- Implement proper test data management and cleanup\n" +
                    "- Test transaction boundaries and rollback scenarios\n" +
                    "- Test query performance and optimization\n" +
                    "- Use @Sql for PostgreSQL test data setup\n" +
                    "- Test database constraints, validations, and migrations\n" +
                    "- Test ElastiCache caching strategies and invalidation\n" +
                    "- Use @Testcontainers for isolated database testing";
        }

        if (request.query().toLowerCase().contains("end-to-end") || request.query().toLowerCase().contains("e2e")) {
            return baseGuidance +
                    "End-to-End Testing:\n" +
                    "- Use Selenium or Playwright for UI testing\n" +
                    "- Test complete user workflows across services\n" +
                    "- Use proper test environment management\n" +
                    "- Implement proper test data management\n" +
                    "- Test cross-service communication\n" +
                    "- Use API testing tools like REST Assured\n" +
                    "- Implement proper test reporting and monitoring\n" +
                    "- Consider performance testing with load tests";
        }

        if (request.query().toLowerCase().contains("tdd") || request.query().toLowerCase().contains("test-driven") ||
                request.query().toLowerCase().contains("test driven development")) {
            return baseGuidance +
                    "Test-Driven Development (TDD) Implementation:\n" +
                    "- Follow Red-Green-Refactor cycle: Write failing test → Make it pass → Refactor\n" +
                    "- Write tests before implementing functionality\n" +
                    "- Start with the simplest failing test case\n" +
                    "- Write minimal code to make tests pass\n" +
                    "- Refactor code while keeping tests green\n" +
                    "- Use TDD for complex business logic and algorithms\n" +
                    "- Practice outside-in TDD for feature development\n" +
                    "- Use acceptance tests to drive development from user perspective\n" +
                    "- Maintain fast test feedback loops (<10 seconds)\n" +
                    "- Focus on behavior specification rather than implementation details";
        }

        if (request.query().toLowerCase().contains("property") || request.query().toLowerCase().contains("jqwik") ||
                request.query().toLowerCase().contains("property-based")) {
            return baseGuidance +
                    "Property-Based Testing with jqwik Framework:\n" +
                    "- Use @Property annotation instead of @Test for property-based tests\n" +
                    "- Define properties that should hold for all valid inputs\n" +
                    "- Use @ForAll annotation to generate test data automatically\n" +
                    "- Configure generators with @Provide methods for custom data types\n" +
                    "- Run minimum 100 iterations per property test for thorough validation\n" +
                    "- Test invariants, round-trip properties, and metamorphic relationships\n" +
                    "- Use shrinking to find minimal failing examples\n" +
                    "- Combine with example-based tests for edge cases\n" +
                    "- Tag property tests with comments referencing design document properties\n" +
                    "- Use format: '**Feature: {feature_name}, Property {number}: {property_text}**'";
        }

        return baseGuidance +
                "General Testing Best Practices:\n" +
                "- Follow the testing pyramid: unit > integration > e2e\n" +
                "- Implement continuous testing in CI/CD pipeline\n" +
                "- Use proper test naming and documentation\n" +
                "- Implement test-driven development (TDD) where appropriate\n" +
                "- Use behavior-driven development (BDD) for complex scenarios\n" +
                "- Maintain test code quality same as production code\n" +
                "- Implement proper test reporting and metrics\n" +
                "- Use mutation testing to validate test quality";
    }

    private List<String> generateTestingRecommendations(AgentConsultationRequest request) {
        return List.of(
                "Use JUnit 5 with Mockito for unit testing",
                "Implement Test-Driven Development (TDD) for complex logic",
                "Use jqwik framework for property-based testing",
                "Implement comprehensive integration tests with Spring Boot Test",
                "Use contract testing with Pact for service boundaries",
                "Test PostgreSQL and ElastiCache data access layers thoroughly",
                "Implement proper test automation and CI/CD integration",
                "Follow testing pyramid principles: unit > integration > e2e",
                "Use TestContainers for isolated database testing",
                "Maintain high test coverage with meaningful, quality tests",
                "Use proper test data management and cleanup strategies",
                "Run property tests with minimum 100 iterations for validation");
    }
}