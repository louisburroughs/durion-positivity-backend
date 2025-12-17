package com.positivity.agent;

import com.positivity.agent.impl.SecurityAgent;
import com.positivity.agent.registry.AgentRegistry;
import com.positivity.agent.registry.DefaultAgentRegistry;
import net.jqwik.api.*;
import org.junit.jupiter.api.BeforeEach;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Property-based test for security compliance validation
 * **Feature: agent-structure, Property 9: Security compliance validation**
 * **Validates: Requirements REQ-007.2, REQ-007.4**
 */
class SecurityComplianceValidationPropertyTest {

    private AgentRegistry registry;
    private SecurityAgent securityAgent;

    @BeforeEach
    void setUp() {
        registry = new DefaultAgentRegistry();
        securityAgent = new SecurityAgent();
        registry.registerAgent(securityAgent);
    }

    private void ensureSetup() {
        if (securityAgent == null) {
            setUp();
        }
    }

    /**
     * Property 9: Security compliance validation
     * For any security implementation request, the system should ensure OWASP
     * compliance
     * and proper authentication/authorization patterns
     */
    @Property(tries = 100)
    void securityComplianceValidation(@ForAll("securityImplementationRequests") AgentConsultationRequest request) {
        // Ensure setup is complete
        ensureSetup();

        // Given: A security agent is available
        assertThat(securityAgent.isAvailable())
                .describedAs("Security agent should be available")
                .isTrue();

        // When: Making a security implementation request
        CompletableFuture<AgentGuidanceResponse> responseFuture = securityAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: The system should provide successful guidance
        assertThat(response.isSuccessful())
                .describedAs("Security agent should provide successful guidance for: %s", request.query())
                .isTrue();

        // And: The guidance should ensure OWASP compliance (REQ-007.2)
        String guidance = response.guidance();
        if (request.query().toLowerCase().contains("owasp") ||
                request.query().toLowerCase().contains("compliance") ||
                request.query().toLowerCase().contains("validation") ||
                request.query().toLowerCase().contains("authorization")) {
            assertThat(guidance.toLowerCase())
                    .describedAs("Guidance should include OWASP compliance recommendations")
                    .containsAnyOf("owasp", "injection", "authentication", "xss", "csrf",
                            "validation", "authorization", "compliance");
        }

        // And: The guidance should include proper authentication patterns (REQ-007.2)
        if (request.query().toLowerCase().contains("auth") ||
                request.query().toLowerCase().contains("jwt") ||
                request.query().toLowerCase().contains("token") ||
                request.query().toLowerCase().contains("login")) {
            assertThat(guidance.toLowerCase())
                    .describedAs("Guidance should include authentication patterns")
                    .containsAnyOf("jwt", "token", "authentication", "spring-security",
                            "oauth", "authorization", "session");
        }

        // And: The guidance should include encryption recommendations (REQ-007.4)
        if (request.query().toLowerCase().contains("encryption") ||
                request.query().toLowerCase().contains("tls") ||
                request.query().toLowerCase().contains("ssl") ||
                request.query().toLowerCase().contains("crypto")) {
            assertThat(guidance.toLowerCase())
                    .describedAs("Guidance should include encryption recommendations")
                    .containsAnyOf("encryption", "tls", "ssl", "aes", "rsa", "crypto",
                            "transit", "rest", "certificate");
        }

        // And: The guidance should be consistent across similar requests
        assertThat(response.confidence())
                .describedAs("Security guidance should have high confidence")
                .isGreaterThanOrEqualTo(0.90);

        // And: The response should include actionable recommendations
        assertThat(response.recommendations())
                .describedAs("Security guidance should include recommendations")
                .isNotEmpty();
    }

    /**
     * OWASP compliance validation test
     * Ensures the system provides comprehensive OWASP compliance guidance
     */
    @Property(tries = 100)
    void owaspComplianceGuidance(@ForAll("owaspVulnerabilityScenarios") String vulnerability) {
        // Ensure setup is complete
        ensureSetup();

        // Given: A request about OWASP vulnerability prevention
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "security",
                "OWASP vulnerability prevention: " + vulnerability,
                Map.of("vulnerability", vulnerability));

        // When: Requesting guidance about OWASP compliance
        CompletableFuture<AgentGuidanceResponse> responseFuture = securityAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: The system should provide successful guidance
        assertThat(response.isSuccessful())
                .describedAs("Should provide guidance for OWASP vulnerability: %s", vulnerability)
                .isTrue();

        // And: The guidance should address the specific vulnerability
        String guidance = response.guidance().toLowerCase();
        assertThat(guidance)
                .describedAs("Guidance should address OWASP vulnerability prevention")
                .containsAnyOf("owasp", "vulnerability", "prevention", "security", "compliance");

        // And: The guidance should include specific prevention techniques
        if (vulnerability.toLowerCase().contains("injection")) {
            assertThat(guidance)
                    .describedAs("Guidance should include injection prevention techniques")
                    .containsAnyOf("parameterized", "query", "validation", "sanitization");
        }

        if (vulnerability.toLowerCase().contains("xss")) {
            assertThat(guidance)
                    .describedAs("Guidance should include XSS prevention techniques")
                    .containsAnyOf("encoding", "escaping", "csp", "content-security-policy");
        }

        if (vulnerability.toLowerCase().contains("csrf")) {
            assertThat(guidance)
                    .describedAs("Guidance should include CSRF prevention techniques")
                    .containsAnyOf("csrf", "token", "same-origin", "samesite");
        }
    }

    /**
     * Authentication and authorization pattern validation test
     * Ensures the system provides consistent authentication/authorization guidance
     */
    @Property(tries = 100)
    void authenticationAuthorizationPatterns(@ForAll("authenticationScenarios") String scenario) {
        // Ensure setup is complete
        ensureSetup();

        // Given: A request about authentication/authorization
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "security",
                "Authentication scenario: " + scenario,
                Map.of("scenario", scenario));

        // When: Requesting guidance about authentication
        CompletableFuture<AgentGuidanceResponse> responseFuture = securityAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: The system should provide successful guidance
        assertThat(response.isSuccessful())
                .describedAs("Should provide guidance for authentication scenario: %s", scenario)
                .isTrue();

        // And: The guidance should include authentication patterns
        String guidance = response.guidance().toLowerCase();
        assertThat(guidance)
                .describedAs("Guidance should include authentication patterns")
                .containsAnyOf("authentication", "authorization", "jwt", "token", "spring-security");

        // And: The guidance should include role-based access control
        if (scenario.toLowerCase().contains("role") || scenario.toLowerCase().contains("rbac")) {
            assertThat(guidance)
                    .describedAs("Guidance should include RBAC patterns")
                    .containsAnyOf("role", "rbac", "permission", "access", "control");
        }

        // And: The guidance should be specific to POS domain when applicable
        if (scenario.toLowerCase().contains("pos") ||
                scenario.toLowerCase().contains("cashier") ||
                scenario.toLowerCase().contains("manager") ||
                scenario.toLowerCase().contains("customer")) {
            assertThat(guidance)
                    .describedAs("Guidance should be specific to POS domain")
                    .containsAnyOf("cashier", "manager", "admin", "customer", "employee");
        }
    }

    /**
     * Encryption implementation validation test
     * Ensures the system provides proper encryption guidance
     */
    @Property(tries = 100)
    void encryptionImplementationGuidance(@ForAll("encryptionScenarios") String scenario) {
        // Ensure setup is complete
        ensureSetup();

        // Given: A request about encryption implementation
        AgentConsultationRequest request = AgentConsultationRequest.create(
                "security",
                "Encryption implementation: " + scenario,
                Map.of("scenario", scenario));

        // When: Requesting guidance about encryption
        CompletableFuture<AgentGuidanceResponse> responseFuture = securityAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();

        // Then: The system should provide successful guidance
        assertThat(response.isSuccessful())
                .describedAs("Should provide guidance for encryption scenario: %s", scenario)
                .isTrue();

        // And: The guidance should include encryption recommendations
        String guidance = response.guidance().toLowerCase();
        assertThat(guidance)
                .describedAs("Guidance should include encryption recommendations")
                .containsAnyOf("encryption", "tls", "ssl", "aes", "rsa", "crypto");

        // And: The guidance should address data at rest encryption
        if (scenario.toLowerCase().contains("rest") || scenario.toLowerCase().contains("database")) {
            assertThat(guidance)
                    .describedAs("Guidance should address data at rest encryption")
                    .containsAnyOf("rest", "database", "column", "field", "storage");
        }

        // And: The guidance should address data in transit encryption
        if (scenario.toLowerCase().contains("transit") || scenario.toLowerCase().contains("tls")) {
            assertThat(guidance)
                    .describedAs("Guidance should address data in transit encryption")
                    .containsAnyOf("transit", "tls", "ssl", "https", "certificate");
        }
    }

    /**
     * Security performance validation test
     * Ensures security guidance maintains acceptable performance
     */
    @Property(tries = 100)
    void securityGuidancePerformance(@ForAll("securityImplementationRequests") AgentConsultationRequest request) {
        // Ensure setup is complete
        ensureSetup();

        // When: Requesting security guidance
        long startTime = System.currentTimeMillis();
        CompletableFuture<AgentGuidanceResponse> responseFuture = securityAgent.provideGuidance(request);
        AgentGuidanceResponse response = responseFuture.join();
        long endTime = System.currentTimeMillis();

        // Then: The response should be timely
        Duration responseTime = Duration.ofMillis(endTime - startTime);
        assertThat(responseTime)
                .describedAs("Security guidance should respond within acceptable time")
                .isLessThan(Duration.ofSeconds(5));

        // And: The response should be successful
        assertThat(response.isSuccessful())
                .describedAs("Security guidance should be successful")
                .isTrue();

        // And: The guidance should be comprehensive
        assertThat(response.guidance().length())
                .describedAs("Security guidance should be comprehensive")
                .isGreaterThan(100);
    }

    // Data providers for property-based testing

    @Provide
    Arbitrary<AgentConsultationRequest> securityImplementationRequests() {
        return Arbitraries.of(
                AgentConsultationRequest.create("security", "JWT authentication implementation", Map.of()),
                AgentConsultationRequest.create("security", "OWASP compliance validation", Map.of()),
                AgentConsultationRequest.create("security", "Spring Security configuration", Map.of()),
                AgentConsultationRequest.create("security", "Authorization patterns for POS system", Map.of()),
                AgentConsultationRequest.create("security", "Input validation and sanitization", Map.of()),
                AgentConsultationRequest.create("security", "Encryption at rest implementation", Map.of()),
                AgentConsultationRequest.create("security", "TLS configuration for microservices", Map.of()),
                AgentConsultationRequest.create("security", "API Gateway security patterns", Map.of()),
                AgentConsultationRequest.create("security", "Secrets management with AWS", Map.of()),
                AgentConsultationRequest.create("security", "Role-based access control for cashiers", Map.of()));
    }

    @Provide
    Arbitrary<String> owaspVulnerabilityScenarios() {
        return Arbitraries.of(
                "SQL injection prevention",
                "Cross-site scripting (XSS) protection",
                "Cross-site request forgery (CSRF) prevention",
                "Broken authentication mitigation",
                "Sensitive data exposure prevention",
                "Security misconfiguration avoidance",
                "Insecure deserialization protection",
                "Known vulnerability management",
                "Insufficient logging detection",
                "Server-side request forgery (SSRF) prevention");
    }

    @Provide
    Arbitrary<String> authenticationScenarios() {
        return Arbitraries.of(
                "Employee JWT authentication",
                "Customer OAuth2 login",
                "Service-to-service authentication",
                "Multi-factor authentication for managers",
                "Role-based access control for cashiers",
                "Session management for POS terminals",
                "API key authentication for third-party integrations",
                "LDAP integration for enterprise users",
                "Social login with Google and Facebook",
                "Biometric authentication for high-security operations");
    }

    @Provide
    Arbitrary<String> encryptionScenarios() {
        return Arbitraries.of(
                "Database encryption at rest",
                "TLS configuration for API endpoints",
                "Customer PII field-level encryption",
                "Payment data tokenization",
                "Inter-service communication encryption",
                "Backup data encryption",
                "Log file encryption",
                "Certificate management for HTTPS",
                "Key rotation strategies",
                "Hardware security module integration");
    }
}