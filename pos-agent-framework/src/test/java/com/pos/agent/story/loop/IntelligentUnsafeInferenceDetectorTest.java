package com.pos.agent.story.loop;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for intelligent unsafe inference detection.
 * Validates that the detector correctly identifies dangerous patterns while
 * avoiding false positives from benign mentions of sensitive keywords.
 */
class IntelligentUnsafeInferenceDetectorTest {

    private final IntelligentUnsafeInferenceDetector detector = new IntelligentUnsafeInferenceDetector();

    // ============ Tests for SAFE examples (should NOT trigger) ============

    @Test
    void testSafeExample_EnsureProperSecurity() {
        String issueText = "Ensure proper security and validation for user inputs";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // Should not detect dangerous patterns - just ensuring best practices
        assertTrue(patterns.get("security").isEmpty(),
                "Should not flag 'Ensure proper security' as dangerous");
    }

    @Test
    void testSafeExample_AuditTrail() {
        String issueText = "Audit includes who/why. Capture reason and notify downstream systems.";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // Should not detect dangerous patterns - audit is just recording
        assertTrue(patterns.get("regulatory").isEmpty(),
                "Should not flag 'Audit trail' as dangerous");
    }

    @Test
    void testSafeExample_ErrorHandling() {
        String issueText = "Implement proper error handling with validation checks";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // Should not detect - "implement proper error handling" is standard practice
        assertTrue(patterns.get("security").isEmpty(),
                "Should not flag standard error handling as dangerous");
    }

    @Test
    void testSafeExample_ComplianceTracking() {
        String issueText = "Add compliance audit logs for tracking changes";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // Should not detect - "add compliance logs" is standard operational requirement
        assertTrue(patterns.get("regulatory").isEmpty(),
                "Should not flag compliance logging as dangerous");
    }

    @Test
    void testSafeExample_ValidationAndSecurity() {
        String issueText = "Implement form validation with standard security practices. Include CSRF tokens.";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // Should not detect - standard practice phrases
        assertTrue(patterns.get("security").isEmpty(),
                "Should not flag standard validation practices");
    }

    // ============ Tests for DANGEROUS examples (should trigger) ============

    @Test
    void testDangerous_DesignAuthenticationSystem() {
        String issueText = "Design authentication system for user login";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // SHOULD detect - asking to design auth system
        assertFalse(patterns.get("security").isEmpty(),
                "Should flag 'design authentication system' as dangerous");
        assertTrue(patterns.get("security").contains("design authentication"),
                "Should contain 'design authentication' pattern");
    }

    @Test
    void testDangerous_BuildComplianceFramework() {
        String issueText = "Build a compliance framework that meets GDPR requirements";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // SHOULD detect - asking to build compliance system
        assertFalse(patterns.get("regulatory").isEmpty(),
                "Should flag 'build compliance' and 'build gdpr' as dangerous");
    }

    @Test
    void testDangerous_ImplementPaymentProcessing() {
        String issueText = "Implement payment processing and transaction handling";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // SHOULD detect - asking to implement payment system
        assertFalse(patterns.get("financial").isEmpty(),
                "Should flag 'implement payment' as dangerous");
        assertTrue(patterns.get("financial").contains("implement payment"),
                "Should contain 'implement payment' pattern");
    }

    @Test
    void testDangerous_CreateEncryptionScheme() {
        String issueText = "Create an encryption scheme for sensitive data protection";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // SHOULD detect - asking to create encryption system
        assertFalse(patterns.get("security").isEmpty(),
                "Should flag 'create encryption' as dangerous");
    }

    @Test
    void testDangerous_DesignLegalContract() {
        String issueText = "Design legal contract templates for service agreements";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // SHOULD detect - asking to design legal documents
        assertFalse(patterns.get("legal").isEmpty(),
                "Should flag 'design legal' as dangerous");
    }

    // ============ Tests for MIXED examples (both safe and dangerous) ============

    @Test
    void testMixed_SafeAndDangerousPatterns() {
        String issueText = "Ensure proper security. Also implement authentication system and add validation.";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // Should detect dangerous "implement authentication" but not "ensure security"
        assertFalse(patterns.get("security").isEmpty(),
                "Should flag 'implement authentication' as dangerous");
        // The detector might flag "implement authentication" but that's the dangerous
        // part
    }

    // ============ Tests for edge cases ============

    @Test
    void testEmptyText() {
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns("");

        assertTrue(patterns.get("security").isEmpty());
        assertTrue(patterns.get("financial").isEmpty());
        assertTrue(patterns.get("legal").isEmpty());
        assertTrue(patterns.get("regulatory").isEmpty());
    }

    @Test
    void testTextWithoutSensitiveKeywords() {
        String issueText = "Create a user dashboard with shopping cart functionality";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        assertTrue(patterns.get("security").isEmpty());
        assertTrue(patterns.get("financial").isEmpty());
        assertTrue(patterns.get("legal").isEmpty());
        assertTrue(patterns.get("regulatory").isEmpty());
    }

    @Test
    void testYourExampleStory() {
        // The exact example from the issue
        String issueText = "Backend Implementation for Story\n" +
                "Original Story: [STORY] Appointment: Reschedule Appointment with Notifications\n" +
                "\n" +
                "Domain: user\n" +
                "\n" +
                "Story Description\n" +
                "Reschedule maintains link to estimate/workorder.\n" +
                "Capture reason and notify downstream systems (optional).\n" +
                "\n" +
                "Acceptance Criteria\n" +
                "Reschedule updates shopmgr.\n" +
                "Conflicts prevented.\n" +
                "Audit includes who/why.\n" +
                "\n" +
                "Integrations\n" +
                "Shopmgr update API; workexec sees updated planned time.\n" +
                "\n" +
                "Data / Entities\n" +
                "AppointmentUpdate, NotificationOutbox, AuditLog\n" +
                "\n" +
                "Backend Requirements\n" +
                "Implement Spring Boot microservices\n" +
                "Create REST API endpoints\n" +
                "Implement business logic and data access\n" +
                "Ensure proper security and validation\n" +
                "\n" +
                "Technical Stack\n" +
                "Spring Boot 4.0.2\n" +
                "Java 21\n" +
                "Spring Data JPA\n" +
                "PostgreSQL/MySQL";

        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // Should NOT flag this story - it's just standard backend work
        // "Audit includes" is not asking to DESIGN audit system
        // "Ensure proper security" is not asking to DESIGN security system
        assertTrue(patterns.get("security").isEmpty(),
                "Should not flag 'Ensure proper security and validation' - it's standard practice");
        assertTrue(patterns.get("regulatory").isEmpty(),
                "Should not flag 'Audit includes' - it's just recording/tracking");
    }

    // ============ Tests for diagnostic message generation ============

    @Test
    void testDiagnosticMessageGeneration() {
        String issueText = "Design authentication and build payment system";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        String diagnostic = detector.generateDiagnostic(patterns);

        assertNotNull(diagnostic);
        assertTrue(diagnostic.contains("🛑 STOP"));
        assertTrue(diagnostic.contains("🔒 Security design request"));
        assertTrue(diagnostic.contains("💰 Financial system design"));
        assertTrue(diagnostic.contains("design authentication"));
        assertTrue(diagnostic.contains("build payment"));
    }

    @Test
    void testDiagnosticMessageEmpty() {
        String issueText = "Create standard REST API with proper validation";
        Map<String, Set<String>> patterns = detector.detectDangerousPatterns(issueText);

        // Debug: print what was detected
        System.out.println("DEBUG: Issue text: " + issueText);
        System.out.println("DEBUG: Patterns found:");
        patterns.forEach((category, patterns_set) -> {
            if (!patterns_set.isEmpty()) {
                System.out.println("  " + category + ": " + patterns_set);
            }
        });

        String diagnostic = detector.generateDiagnostic(patterns);
        System.out.println("DEBUG: Generated diagnostic: [" + diagnostic + "]");

        // Should generate empty diagnostic for safe patterns
        assertTrue(diagnostic.isEmpty(),
                "Should not generate diagnostic for safe patterns");
    }
}
