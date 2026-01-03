package com.pos.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for SecurityContext class.
 */
@DisplayName("SecurityContext")
class SecurityContextTest {

    @Nested
    @DisplayName("Builder Tests")
    class BuilderTests {

        @Test
        @DisplayName("should create SecurityContext using builder")
        void shouldCreateSecurityContextUsingBuilder() {
            // When
            SecurityContext context = SecurityContext.builder().build();

            // Then
            assertThat(context).isNotNull();
            assertThat(context).isInstanceOf(AgentContext.class);
        }

        @Test
        @DisplayName("should set default agentDomain to 'security'")
        void shouldSetDefaultAgentDomain() {
            // When
            SecurityContext context = SecurityContext.builder().build();

            // Then
            assertThat(context.getAgentDomain()).isEqualTo("security");
        }

        @Test
        @DisplayName("should set default contextType to 'security-context'")
        void shouldSetDefaultContextType() {
            // When
            SecurityContext context = SecurityContext.builder().build();

            // Then
            assertThat(context.getContextType()).isEqualTo("security-context");
        }

        @Test
        @DisplayName("should add security control")
        void shouldAddSecurityControl() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .addControl("Access Control")
                    .build();

            // Then
            assertThat(context.getControls()).contains("Access Control");
        }

        @Test
        @DisplayName("should add multiple controls")
        void shouldAddMultipleControls() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .addControl("Authentication")
                    .addControl("Authorization")
                    .addControl("Encryption")
                    .build();

            // Then
            assertThat(context.getControls())
                    .hasSize(3)
                    .contains("Authentication", "Authorization", "Encryption");
        }

        @Test
        @DisplayName("should add security policy")
        void shouldAddSecurityPolicy() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .addPolicy("Password Policy")
                    .build();

            // Then
            assertThat(context.getPolicies()).contains("Password Policy");
        }

        @Test
        @DisplayName("should add security standard")
        void shouldAddSecurityStandard() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .addStandard("OWASP Top 10")
                    .build();

            // Then
            assertThat(context.getStandards()).contains("OWASP Top 10");
        }

        @Test
        @DisplayName("should add threat")
        void shouldAddThreat() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .addThreat("SQL Injection", "HIGH")
                    .build();

            // Then
            assertThat(context.getThreats()).containsEntry("SQL Injection", "HIGH");
        }

        @Test
        @DisplayName("should add mitigation")
        void shouldAddMitigation() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .addMitigation("SQL Injection", "Use parameterized queries")
                    .build();

            // Then
            assertThat(context.getMitigations()).containsEntry("SQL Injection", "Use parameterized queries");
        }

        @Test
        @DisplayName("should add authentication method")
        void shouldAddAuthenticationMethod() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .addAuthenticationMethod("OAuth2")
                    .build();

            // Then
            assertThat(context.getAuthenticationMethods()).contains("OAuth2");
        }

        @Test
        @DisplayName("should add encryption standard")
        void shouldAddEncryptionStandard() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .addEncryptionStandard("AES-256")
                    .build();

            // Then
            assertThat(context.getEncryptionStandards()).contains("AES-256");
        }

        @Test
        @DisplayName("should add compliance requirement")
        void shouldAddComplianceRequirement() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .addComplianceRequirement("GDPR")
                    .build();

            // Then
            assertThat(context.getComplianceRequirements()).contains("GDPR");
        }

        @Test
        @DisplayName("should add audit finding")
        void shouldAddAuditFinding() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .addAuditFinding("Weak passwords", "MEDIUM")
                    .build();

            // Then
            assertThat(context.getAuditFindings()).containsEntry("Weak passwords", "MEDIUM");
        }

        @Test
        @DisplayName("should set risk level")
        void shouldSetRiskLevel() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .riskLevel("HIGH")
                    .build();

            // Then
            assertThat(context.getRiskLevel()).isEqualTo("HIGH");
        }
    }

    @Nested
    @DisplayName("Immutability Tests")
    class ImmutabilityTests {

        @Test
        @DisplayName("should return defensive copy of controls")
        void shouldReturnDefensiveCopyOfControls() {
            // Given
            SecurityContext context = SecurityContext.builder()
                    .addControl("Control 1")
                    .build();

            // When
            var controls = context.getControls();
            controls.add("Hacked Control");

            // Then
            assertThat(context.getControls()).doesNotContain("Hacked Control");
        }

        @Test
        @DisplayName("should return defensive copy of threats")
        void shouldReturnDefensiveCopyOfThreats() {
            // Given
            SecurityContext context = SecurityContext.builder()
                    .addThreat("XSS", "HIGH")
                    .build();

            // When
            var threats = context.getThreats();
            threats.put("New Threat", "CRITICAL");

            // Then
            assertThat(context.getThreats()).doesNotContainKey("New Threat");
        }
    }

    @Nested
    @DisplayName("Real-World Usage Tests")
    class RealWorldUsageTests {

        @Test
        @DisplayName("should create comprehensive security context")
        void shouldCreateComprehensiveSecurityContext() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .description("Production security posture")
                    .addControl("Multi-factor Authentication")
                    .addControl("Role-based Access Control")
                    .addControl("Data Encryption at Rest")
                    .addControl("Data Encryption in Transit")
                    .addPolicy("Password Complexity Policy")
                    .addPolicy("Session Timeout Policy")
                    .addStandard("OWASP Top 10")
                    .addStandard("CIS Benchmarks")
                    .addStandard("NIST Cybersecurity Framework")
                    .addThreat("SQL Injection", "HIGH")
                    .addThreat("XSS", "MEDIUM")
                    .addThreat("CSRF", "MEDIUM")
                    .addMitigation("SQL Injection", "Parameterized queries and input validation")
                    .addMitigation("XSS", "Output encoding and CSP headers")
                    .addAuthenticationMethod("OAuth 2.0")
                    .addAuthenticationMethod("SAML 2.0")
                    .addEncryptionStandard("AES-256-GCM")
                    .addEncryptionStandard("TLS 1.3")
                    .addComplianceRequirement("SOC 2 Type II")
                    .addComplianceRequirement("GDPR")
                    .addComplianceRequirement("HIPAA")
                    .riskLevel("MEDIUM")
                    .requiresAuthentication(true)
                    .requiresTLS13(true)
                    .requiresAuditTrail(true)
                    .build();

            // Then
            assertThat(context.getControls()).hasSize(4);
            assertThat(context.getPolicies()).hasSize(2);
            assertThat(context.getStandards()).hasSize(3);
            assertThat(context.getThreats()).hasSize(3);
            assertThat(context.getMitigations()).hasSize(2);
            assertThat(context.getAuthenticationMethods()).hasSize(2);
            assertThat(context.getEncryptionStandards()).hasSize(2);
            assertThat(context.getComplianceRequirements()).hasSize(3);
            assertThat(context.getRiskLevel()).isEqualTo("MEDIUM");
        }

        @Test
        @DisplayName("should create security context for vulnerability assessment")
        void shouldCreateSecurityContextForVulnerabilityAssessment() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .description("Q4 2025 Vulnerability Assessment")
                    .addThreat("Outdated Dependencies", "HIGH")
                    .addThreat("Missing Security Headers", "LOW")
                    .addAuditFinding("CVE-2025-12345", "CRITICAL")
                    .addAuditFinding("Weak TLS Configuration", "MEDIUM")
                    .riskLevel("HIGH")
                    .build();

            // Then
            assertThat(context.getThreats()).hasSize(2);
            assertThat(context.getAuditFindings()).hasSize(2);
            assertThat(context.getRiskLevel()).isEqualTo("HIGH");
        }

        @Test
        @DisplayName("should create security context for compliance audit")
        void shouldCreateSecurityContextForComplianceAudit() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .description("Annual SOC 2 Audit")
                    .addComplianceRequirement("SOC 2 Type II")
                    .addControl("Logical Access Controls")
                    .addControl("Encryption Controls")
                    .addControl("Change Management")
                    .addPolicy("Data Retention Policy")
                    .addPolicy("Incident Response Policy")
                    .riskLevel("LOW")
                    .requiresAuditTrail(true)
                    .build();

            // Then
            assertThat(context.getComplianceRequirements()).contains("SOC 2 Type II");
            assertThat(context.getControls()).hasSize(3);
            assertThat(context.getPolicies()).hasSize(2);
        }

        @Test
        @DisplayName("should create security context for threat modeling")
        void shouldCreateSecurityContextForThreatModeling() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .description("E-commerce Platform Threat Model")
                    .addThreat("Payment Card Data Theft", "CRITICAL")
                    .addThreat("Account Takeover", "HIGH")
                    .addThreat("DDoS Attack", "MEDIUM")
                    .addMitigation("Payment Card Data Theft", "PCI-DSS compliance and tokenization")
                    .addMitigation("Account Takeover", "MFA and anomaly detection")
                    .addMitigation("DDoS Attack", "CDN and rate limiting")
                    .addStandard("PCI-DSS 4.0")
                    .riskLevel("HIGH")
                    .build();

            // Then
            assertThat(context.getThreats()).hasSize(3);
            assertThat(context.getMitigations()).hasSize(3);
            assertThat(context.getThreats()).containsKey("Payment Card Data Theft");
            assertThat(context.getThreats().get("Payment Card Data Theft")).isEqualTo("CRITICAL");
        }
    }

    @Nested
    @DisplayName("Edge Cases Tests")
    class EdgeCasesTests {

        @Test
        @DisplayName("should handle empty security context")
        void shouldHandleEmptySecurityContext() {
            // When
            SecurityContext context = SecurityContext.builder().build();

            // Then
            assertThat(context.getControls()).isEmpty();
            assertThat(context.getPolicies()).isEmpty();
            assertThat(context.getStandards()).isEmpty();
            assertThat(context.getThreats()).isEmpty();
            assertEquals(context.getRiskLevel(), "medium");
        }

        @Test
        @DisplayName("should handle null risk level")
        void shouldHandleNullRiskLevel() {
            // When
            SecurityContext context = SecurityContext.builder()
                    .riskLevel(null)
                    .build();

            // Then
            assertEquals(context.getRiskLevel(), "medium");
        }
    }
}
