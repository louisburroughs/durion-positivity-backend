package com.pos.agent.story.validation;

/**
 * Centralized manager for all stop phrases used throughout the Story Strengthening Agent.
 * Provides consistent stop phrase format and categorization.
 * 
 * Stop phrases follow the format: "STOP: [reason]"
 * 
 * Requirements: 1.4, 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
 */
public final class StopPhraseManager {
    
    // ========== Validation Stop Phrases (Requirement 10.1, 10.2, 10.3) ==========
    
    /**
     * Emitted when the issue prefix does not contain [BACKEND] [STORY].
     * Requirement: 10.1
     */
    public static final String STOP_PREFIX_NOT_SUPPORTED = "STOP: Issue prefix not supported";
    
    /**
     * Emitted when the issue is not a functional story (may be epic, task, or bug).
     * Requirement: 10.2
     */
    public static final String STOP_NOT_FUNCTIONAL_STORY = "STOP: Issue is not a functional story";
    
    /**
     * Emitted when the repository is not durion-positivity-backend.
     * Requirement: 10.3
     */
    public static final String STOP_REPOSITORY_NOT_IN_SCOPE = "STOP: Repository not in scope";
    
    // ========== Processing Stop Phrases (Requirement 10.4, 10.5, 10.6, 10.7, 10.8) ==========
    
    /**
     * Emitted when story intent cannot be inferred safely from the issue body.
     * Requirement: 10.4
     */
    public static final String STOP_INTENT_CANNOT_BE_INFERRED = "STOP: Story intent cannot be inferred safely";
    
    /**
     * Emitted when the state model is undefined and requires clarification.
     * Requirement: 10.5
     */
    public static final String STOP_STATE_MODEL_UNDEFINED = "STOP: State model undefined";
    
    /**
     * Emitted when the audit contract is unknown and requires clarification.
     * Requirement: 10.6
     */
    public static final String STOP_AUDIT_CONTRACT_UNKNOWN = "STOP: Audit contract unknown";
    
    /**
     * Emitted when the permission model is unclear and requires clarification.
     * Requirement: 10.7
     */
    public static final String STOP_PERMISSION_MODEL_UNCLEAR = "STOP: Permission model unclear";
    
    /**
     * Emitted when required data fields are ambiguous and require clarification.
     * Requirement: 10.8
     */
    public static final String STOP_DATA_FIELDS_AMBIGUOUS = "STOP: Required data fields ambiguous";
    
    // ========== Loop Detection Stop Phrases (Requirement 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7) ==========
    
    /**
     * Emitted when the same section is rewritten more than 2 times without new clarity.
     * Requirement: 11.1
     */
    public static final String STOP_REWRITING_WITHOUT_NEW_INFO = "STOP: Rewriting without new information";
    
    /**
     * Emitted when acceptance criteria exceed 25 Gherkin scenarios.
     * Requirement: 11.2
     */
    public static final String STOP_ACCEPTANCE_CRITERIA_EXCEED_SCOPE = "STOP: Acceptance criteria exceed reasonable scope";
    
    /**
     * Emitted when open questions exceed 10 items.
     * Requirement: 11.3
     */
    public static final String STOP_EXCESSIVE_AMBIGUITY = "STOP: Excessive ambiguity – requires human clarification";
    
    /**
     * Emitted when the agent must infer legal rules.
     * Requirement: 11.4
     */
    public static final String STOP_UNSAFE_INFERENCE_LEGAL = "STOP: Unsafe inference required (legal rules)";
    
    /**
     * Emitted when the agent must infer financial policy.
     * Requirement: 11.5
     */
    public static final String STOP_UNSAFE_INFERENCE_FINANCIAL = "STOP: Unsafe inference required (financial policy)";
    
    /**
     * Emitted when the agent must infer security posture.
     * Requirement: 11.6
     */
    public static final String STOP_UNSAFE_INFERENCE_SECURITY = "STOP: Unsafe inference required (security posture)";
    
    /**
     * Emitted when the agent must infer regulatory compliance.
     * Requirement: 11.7
     */
    public static final String STOP_UNSAFE_INFERENCE_REGULATORY = "STOP: Unsafe inference required (regulatory compliance)";
    
    // ========== Stop Phrase Categories ==========
    
    /**
     * Category for validation-related stop phrases.
     */
    public enum StopPhraseCategory {
        VALIDATION,
        PROCESSING,
        LOOP_DETECTION,
        UNSAFE_INFERENCE
    }
    
    /**
     * Private constructor to prevent instantiation.
     */
    private StopPhraseManager() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Checks if a message is a stop phrase.
     * 
     * @param message Message to check
     * @return true if the message starts with "STOP:", false otherwise
     */
    public static boolean isStopPhrase(String message) {
        return message != null && message.startsWith("STOP:");
    }
    
    /**
     * Extracts the reason from a stop phrase.
     * 
     * @param stopPhrase Stop phrase to extract from
     * @return The reason portion after "STOP: ", or the original message if not a stop phrase
     */
    public static String extractReason(String stopPhrase) {
        if (stopPhrase == null) {
            return null;
        }
        
        if (stopPhrase.startsWith("STOP: ")) {
            return stopPhrase.substring(6);
        }
        
        return stopPhrase;
    }
    
    /**
     * Creates a stop phrase with additional context.
     * 
     * @param baseStopPhrase Base stop phrase constant
     * @param context Additional context to append
     * @return Formatted stop phrase with context
     */
    public static String withContext(String baseStopPhrase, String context) {
        if (context == null || context.trim().isEmpty()) {
            return baseStopPhrase;
        }
        
        return baseStopPhrase + " (" + context + ")";
    }
    
    /**
     * Determines the category of a stop phrase.
     * 
     * @param stopPhrase Stop phrase to categorize
     * @return Category of the stop phrase, or null if not recognized
     */
    public static StopPhraseCategory categorize(String stopPhrase) {
        if (stopPhrase == null) {
            return null;
        }
        
        // Validation stop phrases
        if (stopPhrase.equals(STOP_PREFIX_NOT_SUPPORTED) ||
            stopPhrase.equals(STOP_NOT_FUNCTIONAL_STORY) ||
            stopPhrase.equals(STOP_REPOSITORY_NOT_IN_SCOPE)) {
            return StopPhraseCategory.VALIDATION;
        }
        
        // Processing stop phrases
        if (stopPhrase.equals(STOP_INTENT_CANNOT_BE_INFERRED) ||
            stopPhrase.equals(STOP_STATE_MODEL_UNDEFINED) ||
            stopPhrase.equals(STOP_AUDIT_CONTRACT_UNKNOWN) ||
            stopPhrase.equals(STOP_PERMISSION_MODEL_UNCLEAR) ||
            stopPhrase.equals(STOP_DATA_FIELDS_AMBIGUOUS)) {
            return StopPhraseCategory.PROCESSING;
        }
        
        // Loop detection stop phrases
        if (stopPhrase.equals(STOP_REWRITING_WITHOUT_NEW_INFO) ||
            stopPhrase.equals(STOP_ACCEPTANCE_CRITERIA_EXCEED_SCOPE) ||
            stopPhrase.equals(STOP_EXCESSIVE_AMBIGUITY)) {
            return StopPhraseCategory.LOOP_DETECTION;
        }
        
        // Unsafe inference stop phrases
        if (stopPhrase.equals(STOP_UNSAFE_INFERENCE_LEGAL) ||
            stopPhrase.equals(STOP_UNSAFE_INFERENCE_FINANCIAL) ||
            stopPhrase.equals(STOP_UNSAFE_INFERENCE_SECURITY) ||
            stopPhrase.equals(STOP_UNSAFE_INFERENCE_REGULATORY)) {
            return StopPhraseCategory.UNSAFE_INFERENCE;
        }
        
        // Check if it starts with any known stop phrase (for contextualized versions)
        if (stopPhrase.startsWith(STOP_PREFIX_NOT_SUPPORTED) ||
            stopPhrase.startsWith(STOP_NOT_FUNCTIONAL_STORY) ||
            stopPhrase.startsWith(STOP_REPOSITORY_NOT_IN_SCOPE)) {
            return StopPhraseCategory.VALIDATION;
        }
        
        if (stopPhrase.startsWith(STOP_INTENT_CANNOT_BE_INFERRED) ||
            stopPhrase.startsWith(STOP_STATE_MODEL_UNDEFINED) ||
            stopPhrase.startsWith(STOP_AUDIT_CONTRACT_UNKNOWN) ||
            stopPhrase.startsWith(STOP_PERMISSION_MODEL_UNCLEAR) ||
            stopPhrase.startsWith(STOP_DATA_FIELDS_AMBIGUOUS)) {
            return StopPhraseCategory.PROCESSING;
        }
        
        if (stopPhrase.startsWith(STOP_REWRITING_WITHOUT_NEW_INFO) ||
            stopPhrase.startsWith(STOP_ACCEPTANCE_CRITERIA_EXCEED_SCOPE) ||
            stopPhrase.startsWith(STOP_EXCESSIVE_AMBIGUITY)) {
            return StopPhraseCategory.LOOP_DETECTION;
        }
        
        if (stopPhrase.startsWith(STOP_UNSAFE_INFERENCE_LEGAL) ||
            stopPhrase.startsWith(STOP_UNSAFE_INFERENCE_FINANCIAL) ||
            stopPhrase.startsWith(STOP_UNSAFE_INFERENCE_SECURITY) ||
            stopPhrase.startsWith(STOP_UNSAFE_INFERENCE_REGULATORY)) {
            return StopPhraseCategory.UNSAFE_INFERENCE;
        }
        
        return null;
    }
    
    /**
     * Checks if a stop phrase should produce a rewrite with open questions.
     * 
     * @param stopPhrase Stop phrase to check
     * @return true if the stop phrase should produce a rewrite with open questions
     */
    public static boolean shouldProduceRewrite(String stopPhrase) {
        if (stopPhrase == null) {
            return false;
        }
        
        // Requirements 10.5, 10.6, 10.7, 10.8 specify producing a rewrite with open questions
        return stopPhrase.startsWith(STOP_STATE_MODEL_UNDEFINED) ||
               stopPhrase.startsWith(STOP_AUDIT_CONTRACT_UNKNOWN) ||
               stopPhrase.startsWith(STOP_PERMISSION_MODEL_UNCLEAR) ||
               stopPhrase.startsWith(STOP_DATA_FIELDS_AMBIGUOUS);
    }
}
