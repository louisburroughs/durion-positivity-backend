package com.pos.agent.story.loop;

import com.pos.agent.story.models.TransformedRequirements;

import java.util.*;

/**
 * Detects and prevents processing loops by tracking iterations, counting
 * scenarios/questions,
 * and detecting unsafe inferences.
 * 
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
 */
public class LoopDetector {

    private static final int MAX_REWRITE_ITERATIONS = 2;
    private static final int MAX_ACCEPTANCE_CRITERIA = 25;
    private static final int MAX_OPEN_QUESTIONS = 10;

    // Keywords that indicate unsafe inferences
    // Note: Keywords are mutually exclusive to avoid detection conflicts
    private static final Set<String> LEGAL_KEYWORDS = Set.of(
            "legal", "law", "statute", "contract", "liability", "jurisdiction",
            "litigation", "lawsuit", "attorney", "counsel");

    private static final Set<String> FINANCIAL_KEYWORDS = Set.of(
            "financial", "payment", "transaction", "money", "currency", "tax",
            "accounting", "invoice", "billing", "pricing", "revenue", "cost");

    private static final Set<String> SECURITY_KEYWORDS = Set.of(
            "security", "authentication", "authorization", "encryption", "credential",
            "password", "token", "certificate", "vulnerability", "vulnerabilities",
            "threat", "attack");

    private static final Set<String> REGULATORY_KEYWORDS = Set.of(
            "regulatory", "compliance", "audit", "policy", "governance", "standard",
            "certification", "accreditation", "mandate", "requirement", "regulation",
            "gdpr", "hipaa", "ccpa", "sox", "pci");

    // Track rewrite iterations per section
    private final Map<String, Integer> sectionIterations = new HashMap<>();

    /**
     * Tracks a rewrite iteration for a specific section.
     * 
     * @param sectionName Name of the section being rewritten
     */
    public void trackRewrite(String sectionName) {
        if (sectionName == null || sectionName.trim().isEmpty()) {
            throw new IllegalArgumentException("Section name cannot be null or empty");
        }

        sectionIterations.merge(sectionName, 1, Integer::sum);
    }

    /**
     * Checks if rewrite iterations exceed the threshold for any section.
     * 
     * @return Optional containing stop phrase if threshold exceeded, empty
     *         otherwise
     */
    public Optional<String> checkRewriteIterations() {
        for (Map.Entry<String, Integer> entry : sectionIterations.entrySet()) {
            if (entry.getValue() > MAX_REWRITE_ITERATIONS) {
                return Optional.of(String.format(
                        "STOP: Rewriting without new information (section '%s' rewritten %d times)",
                        entry.getKey(), entry.getValue()));
            }
        }
        return Optional.empty();
    }

    /**
     * Checks if acceptance criteria count exceeds the threshold.
     * 
     * @param transformed Transformed requirements containing acceptance criteria
     * @return Optional containing stop phrase if threshold exceeded, empty
     *         otherwise
     */
    public Optional<String> checkAcceptanceCriteriaCount(TransformedRequirements transformed) {
        if (transformed == null) {
            throw new IllegalArgumentException("Transformed requirements cannot be null");
        }

        int count = transformed.getAcceptanceCriteria().size();
        if (count > MAX_ACCEPTANCE_CRITERIA) {
            return Optional.of(String.format(
                    "STOP: Acceptance criteria exceed reasonable scope (%d scenarios, max %d)",
                    count, MAX_ACCEPTANCE_CRITERIA));
        }
        return Optional.empty();
    }

    /**
     * Checks if open questions count exceeds the threshold.
     * 
     * @param transformed Transformed requirements containing open questions
     * @return Optional containing stop phrase if threshold exceeded, empty
     *         otherwise
     */
    public Optional<String> checkOpenQuestionsCount(TransformedRequirements transformed) {
        if (transformed == null) {
            throw new IllegalArgumentException("Transformed requirements cannot be null");
        }

        int count = transformed.getOpenQuestions().size();
        if (count > MAX_OPEN_QUESTIONS) {
            return Optional.of(String.format(
                    "STOP: Excessive ambiguity – requires human clarification (%d questions, max %d)",
                    count, MAX_OPEN_QUESTIONS));
        }
        return Optional.empty();
    }

    /**
     * Detects unsafe inferences in the issue text.
     * 
     * @param issueText Text to analyze for unsafe inference keywords
     * @return Optional containing stop phrase if unsafe inference detected, empty
     *         otherwise
     */
    public Optional<String> detectUnsafeInference(String issueText) {
        if (issueText == null) {
            throw new IllegalArgumentException("Issue text cannot be null");
        }

        String lowerText = issueText.toLowerCase();

        // Check for legal keywords
        if (containsAnyKeyword(lowerText, LEGAL_KEYWORDS)) {
            return Optional.of("STOP: Unsafe inference required (legal rules detected)");
        }

        // Check for financial keywords
        if (containsAnyKeyword(lowerText, FINANCIAL_KEYWORDS)) {
            return Optional.of("STOP: Unsafe inference required (financial policy detected)");
        }

        // Check for security keywords
        if (containsAnyKeyword(lowerText, SECURITY_KEYWORDS)) {
            return Optional.of("STOP: Unsafe inference required (security posture detected)");
        }

        // Check for regulatory keywords
        if (containsAnyKeyword(lowerText, REGULATORY_KEYWORDS)) {
            return Optional.of("STOP: Unsafe inference required (regulatory compliance detected)");
        }

        return Optional.empty();
    }

    /**
     * Detects unsafe inferences and provides detailed diagnostic information.
     * Returns a detailed report of all keywords found, not just a stop phrase.
     * 
     * @param issueText Text to analyze for unsafe inference keywords
     * @return Optional containing detailed diagnostic message with all keywords
     *         found
     */
    public Optional<String> detectUnsafeInferenceWithDiagnostics(String issueText) {
        if (issueText == null) {
            throw new IllegalArgumentException("Issue text cannot be null");
        }

        String lowerText = issueText.toLowerCase();
        StringBuilder diagnostics = new StringBuilder();
        boolean foundAnyKeywords = false;

        // Check for legal keywords
        Set<String> foundLegal = findMatchingKeywords(lowerText, LEGAL_KEYWORDS);
        if (!foundLegal.isEmpty()) {
            diagnostics.append("📋 Legal keywords detected: ").append(foundLegal).append("\n");
            foundAnyKeywords = true;
        }

        // Check for financial keywords
        Set<String> foundFinancial = findMatchingKeywords(lowerText, FINANCIAL_KEYWORDS);
        if (!foundFinancial.isEmpty()) {
            diagnostics.append("💰 Financial keywords detected: ").append(foundFinancial).append("\n");
            foundAnyKeywords = true;
        }

        // Check for security keywords
        Set<String> foundSecurity = findMatchingKeywords(lowerText, SECURITY_KEYWORDS);
        if (!foundSecurity.isEmpty()) {
            diagnostics.append("🔒 Security keywords detected: ").append(foundSecurity).append("\n");
            foundAnyKeywords = true;
        }

        // Check for regulatory keywords
        Set<String> foundRegulatory = findMatchingKeywords(lowerText, REGULATORY_KEYWORDS);
        if (!foundRegulatory.isEmpty()) {
            diagnostics.append("⚖️ Regulatory keywords detected: ").append(foundRegulatory).append("\n");
            foundAnyKeywords = true;
        }

        if (foundAnyKeywords) {
            diagnostics.insert(0, "🛑 STOP: Unsafe inference required\n\n");
            diagnostics.append("\nAction: Please reword the issue title/description to remove these keywords.\n");
            diagnostics.append(
                    "Reason: AI agents cannot safely infer these domain-specific rules without explicit human guidance.\n");
            return Optional.of(diagnostics.toString());
        }

        return Optional.empty();
    }

    /**
     * Finds all matching keywords from a set that appear in the given text.
     * 
     * @param text     Text to search
     * @param keywords Keywords to find
     * @return Set of keywords that were found in the text
     */
    private Set<String> findMatchingKeywords(String text, Set<String> keywords) {
        Set<String> found = new LinkedHashSet<>();
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                found.add(keyword);
            }
        }
        return found;
    }

    /**
     * Performs all loop detection checks and returns the first stop phrase
     * encountered.
     * 
     * @param transformed Transformed requirements to check
     * @param issueText   Original issue text to check for unsafe inferences
     * @return Optional containing stop phrase if any check fails, empty otherwise
     */
    public Optional<String> performAllChecks(TransformedRequirements transformed, String issueText) {
        // Check rewrite iterations
        Optional<String> rewriteCheck = checkRewriteIterations();
        if (rewriteCheck.isPresent()) {
            return rewriteCheck;
        }

        // Check acceptance criteria count
        Optional<String> criteriaCheck = checkAcceptanceCriteriaCount(transformed);
        if (criteriaCheck.isPresent()) {
            return criteriaCheck;
        }

        // Check open questions count
        Optional<String> questionsCheck = checkOpenQuestionsCount(transformed);
        if (questionsCheck.isPresent()) {
            return questionsCheck;
        }

        // Check for unsafe inferences (with detailed diagnostics)
        Optional<String> inferenceCheck = detectUnsafeInferenceWithDiagnostics(issueText);
        if (inferenceCheck.isPresent()) {
            return inferenceCheck;
        }

        return Optional.empty();
    }

    /**
     * Resets all tracking state.
     */
    public void reset() {
        sectionIterations.clear();
    }

    /**
     * Gets the current iteration count for a section.
     * 
     * @param sectionName Name of the section
     * @return Current iteration count, or 0 if section not tracked
     */
    public int getIterationCount(String sectionName) {
        return sectionIterations.getOrDefault(sectionName, 0);
    }

    /**
     * Checks if text contains any of the specified keywords.
     * 
     * @param text     Text to search
     * @param keywords Keywords to search for
     * @return true if any keyword is found, false otherwise
     */
    private boolean containsAnyKeyword(String text, Set<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Performs loop detection checks using a ProcessingContext.
     * This method integrates with the pipeline orchestration.
     * If issueText is available in the context, provides detailed diagnostics.
     * 
     * @param context Processing context containing state information
     * @return LoopDetectionResult indicating if a loop was detected
     */
    public LoopDetectionResult checkForLoops(ProcessingContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Processing context cannot be null");
        }

        // Check acceptance criteria count
        if (context.getAcceptanceCriteriaCount() > MAX_ACCEPTANCE_CRITERIA) {
            return LoopDetectionResult.loopDetected(
                    "STOP: Acceptance criteria exceed reasonable scope",
                    String.format("%d scenarios, max %d",
                            context.getAcceptanceCriteriaCount(), MAX_ACCEPTANCE_CRITERIA));
        }

        // Check open questions count
        if (context.getOpenQuestionsCount() > MAX_OPEN_QUESTIONS) {
            return LoopDetectionResult.loopDetected(
                    "STOP: Excessive ambiguity – requires human clarification",
                    String.format("%d questions, max %d",
                            context.getOpenQuestionsCount(), MAX_OPEN_QUESTIONS));
        }

        // Check for unsafe inferences - provide detailed diagnostics if issue text is
        // available
        if (context.requiresAnyUnsafeInference()) {
            if (context.getIssueText() != null && !context.getIssueText().isEmpty()) {
                // Use detailed diagnostic message if issue text available
                Optional<String> diagnostic = detectUnsafeInferenceWithDiagnostics(context.getIssueText());
                if (diagnostic.isPresent()) {
                    return LoopDetectionResult.loopDetected(diagnostic.get(), "");
                }
            }

            // Fall back to generic messages if no issue text available
            if (context.requiresLegalInference()) {
                return LoopDetectionResult.loopDetected(
                        "STOP: Unsafe inference required",
                        "legal rules detected");
            }

            if (context.requiresFinancialInference()) {
                return LoopDetectionResult.loopDetected(
                        "STOP: Unsafe inference required",
                        "financial policy detected");
            }

            if (context.requiresSecurityInference()) {
                return LoopDetectionResult.loopDetected(
                        "STOP: Unsafe inference required",
                        "security posture detected");
            }

            if (context.requiresRegulatoryInference()) {
                return LoopDetectionResult.loopDetected(
                        "STOP: Unsafe inference required",
                        "regulatory compliance detected");
            }
        }

        return LoopDetectionResult.noLoop();
    }
}
