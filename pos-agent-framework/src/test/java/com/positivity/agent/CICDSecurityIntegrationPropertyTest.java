package com.positivity.agent;

import com.positivity.agent.impl.CICDPipelineAgent;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.registry.DefaultAgentRegistry;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;

/**
 * Property-based test for CI/CD security integration
 * **Feature: agent-structure, Property 15: CI/CD security integration**
 * **Validates: Requirements REQ-013.2, REQ-013.4**
 */
class CICDSecurityIntegrationPropertyTest {

    private AgentRegistry registry;
    private CICDPipelineAgent cicdAgent;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
        cicdAgent = new CICDPipelineAgent();
        registry.registerAgent(cicdAgent);
    }

    /**
     * Property 15: CI/CD security integration
     * For any pipeline configuration, the CI/CD Pipeline Agent should ensure
     * security scanning, testing automation, and deployment security validation
     */
    @Property(tries = 100)
    void cicdSecurityIntegration(
            @ForAll("pipelineConfigurationRequests") AgentConsultationRequest request) {
        // Given: A CI/CD pipeline agent capable of security integration
        cicdAgent = new CICDPipelineAgent();
        assertThat(cicdAgent.isAvailable())
                .describedAs("CI/CD pipeline agent should be available")
                .isTrue();

        // When: Requesting guidance for pipeline configuration
        AgentGuidanceResponse response = cicdAgent.provideGuidance(request).join();

        // Then: The response should be successful
        assertThat(response.status())
                .describedAs("CI/CD pipeline guidance should be successful")
                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

        // And: The response should contain security scanning guidance
        String guidance = response.guidance();
        assertThat(guidance)
                .describedAs("Guidance should contain security scanning recommendations")
                .containsAnyOf("security scan", "SAST", "DAST", "vulnerability", "OWASP", "dependency check");

        // And: The response should include testing automation
        assertThat(guidance)
                .describedAs("Guidance should include testing automation")
                .containsAnyOf("test automation", "unit test", "integration test", "contract test", "security test");

        // And: The response should include deployment security
        assertThat(guidance)
                .describedAs("Guidance should include deployment security")
                .containsAnyOf("deployment security", "container security", "image scanning", "security gate");

        // And: The response should be timely
        assertThat(response.processingTime())
                .describedAs("Response time should be within acceptable limits")
                .isLessThan(Duration.ofSeconds(3));

        // And: The response should have high confidence for CI/CD guidance
        assertThat(response.confidence())
                .describedAs("Confidence should be high for CI/CD guidance")
                .isGreaterThan(0.85);
    }

    /**
     * Property 15b: Security scanning pipeline integration
     * For any security scanning request, the system should provide
     * comprehensive SAST, DAST, and dependency scanning guidance
     */
    @Property(tries = 100)
    void securityScanningPipelineIntegration(
            @ForAll("securityScanningRequests") AgentConsultationRequest request) {
        // When: Requesting guidance for security scanning
        AgentGuidanceResponse response = cicdAgent.provideGuidance(request).join();

        // Then: The response should be successful
        assertThat(response.status())
                .describedAs("Security scanning guidance should be successful")
                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

        String guidance = response.guidance();
        String query = request.query().toLowerCase();

        // And: The response should contain appropriate scanning type guidance
        if (query.contains("sast") || query.contains("static")) {
            assertThat(guidance)
                    .describedAs("SAST guidance should contain static analysis tools")
                    .containsAnyOf("SonarQube", "SpotBugs", "static analysis", "code quality");
        } else if (query.contains("dast") || query.contains("dynamic")) {
            assertThat(guidance)
                    .describedAs("DAST guidance should contain dynamic testing tools")
                    .containsAnyOf("OWASP ZAP", "dynamic testing", "runtime security", "penetration test");
        } else if (query.contains("dependency") || query.contains("vulnerability")) {
            assertThat(guidance)
                    .describedAs("Dependency scanning should contain vulnerability tools")
                    .containsAnyOf("OWASP Dependency Check", "Snyk", "vulnerability scan", "dependency security");
        }

        // And: The response should include security thresholds
        assertThat(guidance)
                .describedAs("Guidance should include security thresholds")
                .containsAnyOf("threshold", "CVSS", "HIGH", "CRITICAL", "quality gate");
    }

    /**
     * Property 15c: Testing pipeline automation consistency
     * For any testing pipeline request, the system should provide
     * comprehensive testing strategies including security testing
     */
    @Property(tries = 100)
    void testingPipelineAutomationConsistency(
            @ForAll("testingPipelineRequests") AgentConsultationRequest request) {
        // When: Requesting guidance for testing pipeline automation
        AgentGuidanceResponse response = cicdAgent.provideGuidance(request).join();

        // Then: The response should be successful
        assertThat(response.status())
                .describedAs("Testing pipeline guidance should be successful")
                .isEqualTo(AgentGuidanceResponse.ResponseStatus.SUCCESS);

        String guidance = response.guidance();

        // And: The response should contain comprehensive testing strategy
        assertThat(guidance)
                .describedAs("Guidance should contain comprehensive testing strategy")
                .containsAnyOf("unit test", "integration test", "contract test", "security test");

        // And: The response should include testing tools and frameworks
        assertThat(guidance)
                .describedAs("Guidance should include testing tools and frameworks")
                .containsAnyOf("JUnit", "TestContainers", "Surefire", "Failsafe", "JaCoCo");

        // And: The response should include test automation patterns
        assertThat(guidance)
                .describedAs("Guidance should include test automation patterns")
                .containsAnyOf("test automation", "pipeline", "coverage", "quality gate", "reporting");
    }

    @Provide
    Arbitrary<AgentConsultationRequest> pipelineConfigurationRequests() {
        return Combinators.combine(
                Arbitraries.of(
                        "CI/CD pipeline configuration for microservices",
                        "build pipeline setup with security scanning",
                        "testing pipeline automation configuration",
                        "deployment pipeline with security gates",
                        "Maven build pipeline with security integration",
                        "Gradle build pipeline with testing automation",
                        "Docker build pipeline with container scanning",
                        "Kubernetes deployment pipeline configuration",
                        "Jenkins pipeline with security and testing",
                        "GitHub Actions workflow with comprehensive testing"),
                Arbitraries.of("cicd", "pipeline", "automation", "build"),
                Arbitraries.maps(
                        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(3).ofMaxLength(10),
                        Arbitraries.of("value1", "value2", "value3")).ofMinSize(1).ofMaxSize(3))
                .as((query, domain, context) -> AgentConsultationRequest.create(domain, query, context));
    }

    @Provide
    Arbitrary<AgentConsultationRequest> securityScanningRequests() {
        return Combinators.combine(
                Arbitraries.of(
                        "SAST security scanning integration",
                        "DAST dynamic security testing setup",
                        "dependency vulnerability scanning configuration",
                        "container security scanning pipeline",
                        "OWASP security scanning automation",
                        "SonarQube static analysis integration",
                        "security quality gates configuration",
                        "vulnerability threshold management",
                        "security scanning reporting setup",
                        "automated security testing pipeline"),
                Arbitraries.of("cicd", "security", "scanning"),
                Arbitraries.maps(
                        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(3).ofMaxLength(10),
                        Arbitraries.of("value1", "value2", "value3")).ofMinSize(1).ofMaxSize(3))
                .as((query, domain, context) -> AgentConsultationRequest.create(domain, query, context));
    }

    @Provide
    Arbitrary<AgentConsultationRequest> testingPipelineRequests() {
        return Combinators.combine(
                Arbitraries.of(
                        "testing pipeline automation setup",
                        "unit testing integration in CI/CD",
                        "integration testing with TestContainers",
                        "contract testing pipeline configuration",
                        "security testing automation",
                        "test coverage reporting setup",
                        "JUnit testing pipeline integration",
                        "Maven testing configuration",
                        "Gradle testing automation",
                        "comprehensive testing strategy"),
                Arbitraries.of("cicd", "testing", "automation"),
                Arbitraries.maps(
                        Arbitraries.strings().withCharRange('a', 'z').ofMinLength(3).ofMaxLength(10),
                        Arbitraries.of("value1", "value2", "value3")).ofMinSize(1).ofMaxSize(3))
                .as((query, domain, context) -> AgentConsultationRequest.create(domain, query, context));
    }
}