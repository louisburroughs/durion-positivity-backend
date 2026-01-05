package com.pos.agent.story.loop;

import com.pos.agent.story.models.GherkinScenario;
import com.pos.agent.story.models.OpenQuestion;
import com.pos.agent.story.models.TransformedRequirements;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for LoopDetector.
 * 
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
 */
class LoopDetectorTest {

    private LoopDetector detector;

    @BeforeEach
    void setUp() {
        detector = new LoopDetector();
    }

    // ========== Rewrite Iteration Tests ==========

    @Test
    void testTrackRewrite_SingleIteration() {
        detector.trackRewrite("Intent");
        assertEquals(1, detector.getIterationCount("Intent"));
    }

    @Test
    void testTrackRewrite_MultipleIterations() {
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");
        assertEquals(3, detector.getIterationCount("Intent"));
    }

    @Test
    void testTrackRewrite_MultipleSections() {
        detector.trackRewrite("Intent");
        detector.trackRewrite("Actors");
        detector.trackRewrite("Intent");

        assertEquals(2, detector.getIterationCount("Intent"));
        assertEquals(1, detector.getIterationCount("Actors"));
    }

    @Test
    void testTrackRewrite_NullSectionName() {
        assertThrows(IllegalArgumentException.class, () -> detector.trackRewrite(null));
    }

    @Test
    void testTrackRewrite_EmptySectionName() {
        assertThrows(IllegalArgumentException.class, () -> detector.trackRewrite(""));
    }

    @Test
    void testCheckRewriteIterations_BelowThreshold() {
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");

        Optional<String> result = detector.checkRewriteIterations();
        assertFalse(result.isPresent());
    }

    @Test
    void testCheckRewriteIterations_AtThreshold() {
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");

        Optional<String> result = detector.checkRewriteIterations();
        assertFalse(result.isPresent());
    }

    @Test
    void testCheckRewriteIterations_ExceedsThreshold() {
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");

        Optional<String> result = detector.checkRewriteIterations();
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("STOP: Rewriting without new information"));
        assertTrue(result.get().contains("Intent"));
        assertTrue(result.get().contains("3 times"));
    }

    @Test
    void testCheckRewriteIterations_MultipleSectionsOneExceeds() {
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");
        detector.trackRewrite("Actors");

        Optional<String> result = detector.checkRewriteIterations();
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Intent"));
    }

    // ========== Acceptance Criteria Count Tests ==========

    @Test
    void testCheckAcceptanceCriteriaCount_BelowThreshold() {
        TransformedRequirements transformed = createTransformedWithScenarios(10);

        Optional<String> result = detector.checkAcceptanceCriteriaCount(transformed);
        assertFalse(result.isPresent());
    }

    @Test
    void testCheckAcceptanceCriteriaCount_AtThreshold() {
        TransformedRequirements transformed = createTransformedWithScenarios(25);

        Optional<String> result = detector.checkAcceptanceCriteriaCount(transformed);
        assertFalse(result.isPresent());
    }

    @Test
    void testCheckAcceptanceCriteriaCount_ExceedsThreshold() {
        TransformedRequirements transformed = createTransformedWithScenarios(26);

        Optional<String> result = detector.checkAcceptanceCriteriaCount(transformed);
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("STOP: Acceptance criteria exceed reasonable scope"));
        assertTrue(result.get().contains("26 scenarios"));
        assertTrue(result.get().contains("max 25"));
    }

    @Test
    void testCheckAcceptanceCriteriaCount_ExactlyAtBoundary() {
        // Test exactly at 25 (should pass)
        TransformedRequirements at25 = createTransformedWithScenarios(25);
        assertFalse(detector.checkAcceptanceCriteriaCount(at25).isPresent());

        // Test exactly at 26 (should fail)
        TransformedRequirements at26 = createTransformedWithScenarios(26);
        assertTrue(detector.checkAcceptanceCriteriaCount(at26).isPresent());
    }

    @Test
    void testCheckAcceptanceCriteriaCount_NullTransformed() {
        assertThrows(IllegalArgumentException.class,
                () -> detector.checkAcceptanceCriteriaCount(null));
    }

    // ========== Open Questions Count Tests ==========

    @Test
    void testCheckOpenQuestionsCount_BelowThreshold() {
        TransformedRequirements transformed = createTransformedWithQuestions(5);

        Optional<String> result = detector.checkOpenQuestionsCount(transformed);
        assertFalse(result.isPresent());
    }

    @Test
    void testCheckOpenQuestionsCount_AtThreshold() {
        TransformedRequirements transformed = createTransformedWithQuestions(10);

        Optional<String> result = detector.checkOpenQuestionsCount(transformed);
        assertFalse(result.isPresent());
    }

    @Test
    void testCheckOpenQuestionsCount_ExceedsThreshold() {
        TransformedRequirements transformed = createTransformedWithQuestions(11);

        Optional<String> result = detector.checkOpenQuestionsCount(transformed);
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("STOP: Excessive ambiguity"));
        assertTrue(result.get().contains("11 questions"));
        assertTrue(result.get().contains("max 10"));
    }

    @Test
    void testCheckOpenQuestionsCount_ExactlyAtBoundary() {
        // Test exactly at 10 (should pass)
        TransformedRequirements at10 = createTransformedWithQuestions(10);
        assertFalse(detector.checkOpenQuestionsCount(at10).isPresent());

        // Test exactly at 11 (should fail)
        TransformedRequirements at11 = createTransformedWithQuestions(11);
        assertTrue(detector.checkOpenQuestionsCount(at11).isPresent());
    }

    @Test
    void testCheckOpenQuestionsCount_NullTransformed() {
        assertThrows(IllegalArgumentException.class,
                () -> detector.checkOpenQuestionsCount(null));
    }

    // ========== Unsafe Inference Detection Tests ==========

    @Test
    void testDetectUnsafeInference_LegalKeywords() {
        String[] legalTexts = {
                "We need to ensure legal compliance",
                "The contract requires specific terms",
                "This creates liability issues",
                "Consult with legal counsel",
                "Review the statute requirements"
        };

        for (String text : legalTexts) {
            Optional<String> result = detector.detectUnsafeInference(text);
            assertTrue(result.isPresent(), "Should detect legal keyword in: " + text);
            assertTrue(result.get().contains("STOP: Unsafe inference required"));
            assertTrue(result.get().contains("legal rules"));
        }
    }

    @Test
    void testDetectUnsafeInference_FinancialKeywords() {
        String[] financialTexts = {
                "Process payment transactions",
                "Calculate tax amounts",
                "Generate financial reports",
                "Handle billing and invoicing",
                "Track revenue and costs"
        };

        for (String text : financialTexts) {
            Optional<String> result = detector.detectUnsafeInference(text);
            assertTrue(result.isPresent(), "Should detect financial keyword in: " + text);
            assertTrue(result.get().contains("STOP: Unsafe inference required"));
            assertTrue(result.get().contains("financial policy"));
        }
    }

    @Test
    void testDetectUnsafeInference_SecurityKeywords() {
        String[] securityTexts = {
                "Implement authentication system",
                "Store encrypted passwords",
                "Validate security tokens",
                "Protect against vulnerabilities",
                "Handle authorization rules"
        };

        for (String text : securityTexts) {
            Optional<String> result = detector.detectUnsafeInference(text);
            assertTrue(result.isPresent(), "Should detect security keyword in: " + text);
            assertTrue(result.get().contains("STOP: Unsafe inference required"));
            assertTrue(result.get().contains("security posture"));
        }
    }

    @Test
    void testDetectUnsafeInference_RegulatoryKeywords() {
        String[] regulatoryTexts = {
                "Meet regulatory requirements",
                "Ensure compliance with standards",
                "Follow audit policies",
                "Obtain certification",
                "Adhere to governance rules",
                "This feature must comply with GDPR regulations",
                "HIPAA compliance is mandatory",
                "This violates PCI DSS standards"
        };

        for (String text : regulatoryTexts) {
            Optional<String> result = detector.detectUnsafeInference(text);
            assertTrue(result.isPresent(), "Should detect regulatory keyword in: " + text);
            assertTrue(result.get().contains("STOP: Unsafe inference required"));
            assertTrue(result.get().contains("regulatory compliance"));
        }
    }

    @Test
    void testDetectUnsafeInference_SafeText() {
        String safeText = "Display a list of products with their names and descriptions";

        Optional<String> result = detector.detectUnsafeInference(safeText);
        assertFalse(result.isPresent());
    }

    @Test
    void testDetectUnsafeInference_CaseInsensitive() {
        String[] texts = {
                "LEGAL compliance required",
                "Financial POLICY needed",
                "Security AUTHENTICATION",
                "REGULATORY standards"
        };

        for (String text : texts) {
            Optional<String> result = detector.detectUnsafeInference(text);
            assertTrue(result.isPresent(), "Should detect keyword regardless of case: " + text);
        }
    }

    @Test
    void testDetectUnsafeInference_NullText() {
        assertThrows(IllegalArgumentException.class,
                () -> detector.detectUnsafeInference(null));
    }

    @Test
    void testDetectUnsafeInferenceWithDiagnostics_SecurityKeyword() {
        String text = "[BACKEND] [STORY] Implement user authentication\n" +
                "As a user, I want to authenticate with a password...\n" +
                "Acceptance Criteria: User can login with credentials.";

        Optional<String> result = detector.detectUnsafeInferenceWithDiagnostics(text);

        assertTrue(result.isPresent());
        String diagnostics = result.get();
        assertTrue(diagnostics.contains("STOP: Unsafe inference required"));
        assertTrue(diagnostics.contains("🔒 Security keywords detected"));
        assertTrue(diagnostics.contains("authentication"));
        assertTrue(diagnostics.contains("password"));
    }

    @Test
    void testDetectUnsafeInferenceWithDiagnostics_MultipleCategories() {
        String text = "[BACKEND] [STORY] Implement transaction security and audit trail\n" +
                "Requirements: authenticate users, encrypt data, audit all transactions\n" +
                "Financial compliance is required.";
        Optional<String> result = detector.detectUnsafeInferenceWithDiagnostics(text);

        assertTrue(result.isPresent());
        String diagnostics = result.get();
        assertTrue(diagnostics.contains("STOP: Unsafe inference required"));
        assertTrue(diagnostics.contains("🔒 Security keywords detected"));
        assertTrue(diagnostics.contains("💰 Financial keywords detected"));
        assertTrue(diagnostics.contains("Action: Please reword the issue"));
    }

    // ========== Combined Checks Tests ==========

    @Test
    void testPerformAllChecks_AllPass() {
        TransformedRequirements transformed = createTransformedWithScenarios(10);
        String issueText = "Display a list of products";

        Optional<String> result = detector.performAllChecks(transformed, issueText);
        assertFalse(result.isPresent());
    }

    @Test
    void testPerformAllChecks_RewriteIterationsFails() {
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");

        TransformedRequirements transformed = createTransformedWithScenarios(10);
        String issueText = "Display a list of products";

        Optional<String> result = detector.performAllChecks(transformed, issueText);
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Rewriting without new information"));
    }

    @Test
    void testPerformAllChecks_AcceptanceCriteriaFails() {
        TransformedRequirements transformed = createTransformedWithScenarios(26);
        String issueText = "Display a list of products";

        Optional<String> result = detector.performAllChecks(transformed, issueText);
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Acceptance criteria exceed reasonable scope"));
    }

    @Test
    void testPerformAllChecks_OpenQuestionsFails() {
        TransformedRequirements transformed = createTransformedWithQuestions(11);
        String issueText = "Display a list of products";

        Optional<String> result = detector.performAllChecks(transformed, issueText);
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Excessive ambiguity"));
    }

    @Test
    void testPerformAllChecks_UnsafeInferenceFails() {
        TransformedRequirements transformed = createTransformedWithScenarios(10);
        String issueText = "Implement GDPR compliance";

        Optional<String> result = detector.performAllChecks(transformed, issueText);
        assertTrue(result.isPresent());
        assertTrue(result.get().contains("Unsafe inference required"));
    }

    @Test
    void testPerformAllChecks_ReturnsFirstFailure() {
        // Set up multiple failures
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");

        TransformedRequirements transformed = createTransformedWithScenarios(26);
        String issueText = "Implement GDPR compliance";

        Optional<String> result = detector.performAllChecks(transformed, issueText);
        assertTrue(result.isPresent());
        // Should return the first failure (rewrite iterations)
        assertTrue(result.get().contains("Rewriting without new information"));
    }

    // ========== Reset Tests ==========

    @Test
    void testReset_ClearsIterations() {
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");
        detector.trackRewrite("Actors");

        detector.reset();

        assertEquals(0, detector.getIterationCount("Intent"));
        assertEquals(0, detector.getIterationCount("Actors"));
    }

    @Test
    void testReset_AllowsNewTracking() {
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");
        detector.trackRewrite("Intent");

        detector.reset();

        detector.trackRewrite("Intent");
        assertEquals(1, detector.getIterationCount("Intent"));

        Optional<String> result = detector.checkRewriteIterations();
        assertFalse(result.isPresent());
    }

    // ========== Helper Methods ==========

    private TransformedRequirements createTransformedWithScenarios(int count) {
        List<GherkinScenario> scenarios = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            scenarios.add(new GherkinScenario(
                    "Scenario " + i,
                    Arrays.asList("Given condition " + i),
                    Arrays.asList("When action " + i),
                    Arrays.asList("Then outcome " + i)));
        }

        return new TransformedRequirements(
                "# Header",
                "Intent statement",
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                scenarios,
                new ArrayList<>(),
                new ArrayList<>());
    }

    private TransformedRequirements createTransformedWithQuestions(int count) {
        List<OpenQuestion> questions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            questions.add(new OpenQuestion(
                    "Question " + i + "?",
                    "Why it matters " + i,
                    "Impact " + i));
        }

        return new TransformedRequirements(
                "# Header",
                "Intent statement",
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                new ArrayList<>(),
                questions);
    }
}
