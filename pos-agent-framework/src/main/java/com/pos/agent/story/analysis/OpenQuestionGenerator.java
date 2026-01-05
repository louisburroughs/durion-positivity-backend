package com.pos.agent.story.analysis;

import com.pos.agent.story.models.OpenQuestion;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Generates open questions for identified ambiguities and uncertainties.
 * 
 * Creates structured open questions with:
 * - Question text describing the ambiguity
 * - "Why it matters" impact description
 * - Categorization by ambiguity type
 * 
 * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8
 */
public class OpenQuestionGenerator {

    /**
     * Ambiguity categories for open questions.
     */
    public enum AmbiguityType {
        REQUIRED_FIELDS,
        PERMISSION_RULES,
        STATE_TRANSITIONS,
        AUDIT_CONTRACTS,
        UNIQUENESS_IDEMPOTENCY,
        DATA_VALIDATION,
        ERROR_HANDLING,
        BUSINESS_RULES,
        INTEGRATION_POINTS
    }

    // Patterns to detect different types of ambiguities
    private static final Pattern FIELD_AMBIGUITY = Pattern.compile(
            "(?i)\\b(fields?|attributes?|propert(y|ies)|data|values?|parameters?)\\b.*\\b(unclear|unknown|unspecified|ambiguous|missing)\\b");

    private static final Pattern PERMISSION_AMBIGUITY = Pattern.compile(
            "(?i)\\b(permissions?|authorization|access|roles?|privileges?)\\b.*\\b(unclear|unknown|unspecified|ambiguous)\\b");

    private static final Pattern STATE_AMBIGUITY = Pattern.compile(
            "(?i)\\b(states?|status(es)?|lifecycle|transitions?|flows?)\\b.*\\b(unclear|unknown|unspecified|ambiguous|incomplete)\\b");

    private static final Pattern AUDIT_AMBIGUITY = Pattern.compile(
            "(?i)\\b(audits?|events?|logs?|tracking|contracts?)\\b.*\\b(unclear|unknown|unspecified|ambiguous)\\b");

    private static final Pattern UNIQUENESS_AMBIGUITY = Pattern.compile(
            "(?i)\\b(uniques?|duplicates?|constraints?|idempotent|idempotency|once)\\b.*\\b(unclear|unknown|unspecified|ambiguous)\\b");

    /**
     * Generates open questions from a list of identified ambiguities.
     * 
     * @param ambiguities List of ambiguity descriptions
     * @return List of structured open questions
     */
    public List<OpenQuestion> generateOpenQuestions(List<String> ambiguities) {
        if (ambiguities == null || ambiguities.isEmpty()) {
            return Collections.emptyList();
        }

        List<OpenQuestion> questions = new ArrayList<>();

        for (String ambiguity : ambiguities) {
            if (ambiguity == null || ambiguity.trim().isEmpty()) {
                continue;
            }

            AmbiguityType type = categorizeAmbiguity(ambiguity);
            OpenQuestion question = createOpenQuestion(ambiguity, type);
            questions.add(question);
        }

        return questions;
    }

    /**
     * Creates an open question for a specific ambiguity.
     * 
     * @param ambiguityText Description of the ambiguity
     * @param type          Category of ambiguity
     * @return Structured open question
     */
    public OpenQuestion createOpenQuestion(String ambiguityText, AmbiguityType type) {
        String question = generateQuestionText(ambiguityText, type);
        String whyItMatters = generateWhyItMatters(type);
        String impact = generateImpactDescription(type);

        return new OpenQuestion(question, whyItMatters, impact);
    }

    /**
     * Categorizes an ambiguity by type based on pattern matching.
     * 
     * @param ambiguityText Description of the ambiguity
     * @return Ambiguity type category
     */
    public AmbiguityType categorizeAmbiguity(String ambiguityText) {
        if (FIELD_AMBIGUITY.matcher(ambiguityText).find()) {
            return AmbiguityType.REQUIRED_FIELDS;
        }
        if (PERMISSION_AMBIGUITY.matcher(ambiguityText).find()) {
            return AmbiguityType.PERMISSION_RULES;
        }
        if (STATE_AMBIGUITY.matcher(ambiguityText).find()) {
            return AmbiguityType.STATE_TRANSITIONS;
        }
        if (AUDIT_AMBIGUITY.matcher(ambiguityText).find()) {
            return AmbiguityType.AUDIT_CONTRACTS;
        }
        if (UNIQUENESS_AMBIGUITY.matcher(ambiguityText).find()) {
            return AmbiguityType.UNIQUENESS_IDEMPOTENCY;
        }

        // Default to business rules if no specific pattern matches
        return AmbiguityType.BUSINESS_RULES;
    }

    /**
     * Generates question text for an ambiguity.
     * 
     * @param ambiguityText Description of the ambiguity
     * @param type          Category of ambiguity
     * @return Question text
     */
    private String generateQuestionText(String ambiguityText, AmbiguityType type) {
        // If the ambiguity text is already a question, use it
        if (ambiguityText.trim().endsWith("?")) {
            return ambiguityText.trim();
        }

        // Otherwise, convert it to a question based on type
        return switch (type) {
            case REQUIRED_FIELDS -> "What are the required fields and their validation rules? " + ambiguityText;
            case PERMISSION_RULES -> "What are the permission rules and who has access? " + ambiguityText;
            case STATE_TRANSITIONS -> "What are the valid state transitions and lifecycle rules? " + ambiguityText;
            case AUDIT_CONTRACTS ->
                "What audit events should be logged and what is the event contract? " + ambiguityText;
            case UNIQUENESS_IDEMPOTENCY ->
                "What are the uniqueness constraints and idempotency requirements? " + ambiguityText;
            case DATA_VALIDATION -> "What are the data validation rules? " + ambiguityText;
            case ERROR_HANDLING -> "How should errors be handled in this scenario? " + ambiguityText;
            case BUSINESS_RULES -> "What are the business rules that apply? " + ambiguityText;
            case INTEGRATION_POINTS -> "What are the integration requirements and contracts? " + ambiguityText;
        };
    }

    /**
     * Generates "why it matters" explanation for an ambiguity type.
     * 
     * @param type Category of ambiguity
     * @return Why it matters explanation
     */
    private String generateWhyItMatters(AmbiguityType type) {
        return switch (type) {
            case REQUIRED_FIELDS -> "Required fields determine data completeness and validation logic";
            case PERMISSION_RULES -> "Permission rules affect security and access control implementation";
            case STATE_TRANSITIONS -> "State transitions define valid workflows and prevent invalid operations";
            case AUDIT_CONTRACTS -> "Audit contracts ensure compliance and enable troubleshooting";
            case UNIQUENESS_IDEMPOTENCY ->
                "Uniqueness and idempotency prevent data corruption and duplicate operations";
            case DATA_VALIDATION -> "Data validation ensures data integrity and prevents invalid states";
            case ERROR_HANDLING -> "Error handling affects user experience and system reliability";
            case BUSINESS_RULES -> "Business rules define core system behavior and constraints";
            case INTEGRATION_POINTS -> "Integration contracts define system boundaries and dependencies";
        };
    }

    /**
     * Generates impact description for an ambiguity type.
     * 
     * @param type Category of ambiguity
     * @return Impact description
     */
    private String generateImpactDescription(AmbiguityType type) {
        return switch (type) {
            case REQUIRED_FIELDS ->
                "Without clear field requirements, implementation may be incomplete or incorrect, leading to data quality issues and validation bugs";
            case PERMISSION_RULES ->
                "Without clear permission rules, the system may have security vulnerabilities or incorrectly restrict legitimate access";
            case STATE_TRANSITIONS ->
                "Without clear state transitions, the system may allow invalid operations or enter inconsistent states";
            case AUDIT_CONTRACTS ->
                "Without clear audit contracts, compliance requirements may not be met and troubleshooting will be difficult";
            case UNIQUENESS_IDEMPOTENCY ->
                "Without clear uniqueness and idempotency rules, the system may create duplicate data or process operations multiple times";
            case DATA_VALIDATION ->
                "Without clear validation rules, invalid data may enter the system causing errors and data corruption";
            case ERROR_HANDLING ->
                "Without clear error handling, users may experience poor error messages and the system may fail unpredictably";
            case BUSINESS_RULES ->
                "Without clear business rules, implementation may not match business intent and may require rework";
            case INTEGRATION_POINTS ->
                "Without clear integration contracts, system integration may fail or behave unpredictably";
        };
    }

    /**
     * Validates that an open question has the required structure.
     * 
     * @param question Open question to validate
     * @return true if valid, false otherwise
     */
    public boolean validateOpenQuestion(OpenQuestion question) {
        if (question == null) {
            return false;
        }

        // Check that question text is present and non-empty
        if (question.getQuestion() == null || question.getQuestion().trim().isEmpty()) {
            return false;
        }

        // Check that why it matters is present and non-empty
        if (question.getWhyItMatters() == null || question.getWhyItMatters().trim().isEmpty()) {
            return false;
        }

        // Check that impact is present and non-empty
        if (question.getImpact() == null || question.getImpact().trim().isEmpty()) {
            return false;
        }

        return true;
    }

    /**
     * Formats an open question for output.
     * 
     * @param question Open question to format
     * @return Formatted question text
     */
    public String formatOpenQuestion(OpenQuestion question) {
        StringBuilder formatted = new StringBuilder();
        formatted.append("**Question:** ").append(question.getQuestion()).append("\n\n");
        formatted.append("**Why it matters:** ").append(question.getWhyItMatters()).append("\n\n");
        formatted.append("**Impact:** ").append(question.getImpact());
        return formatted.toString();
    }
}
