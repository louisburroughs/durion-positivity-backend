package com.pos.agent.story.validation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for StopPhraseManager.
 * 
 * Requirements: 1.4, 10.1, 10.2, 10.3, 10.4, 10.5, 10.6, 10.7, 10.8, 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
 */
class StopPhraseManagerTest {
    
    // ========== Stop Phrase Constants Tests ==========
    
    @Test
    void testValidationStopPhrases_HaveCorrectFormat() {
        assertTrue(StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED.startsWith("STOP:"));
        assertTrue(StopPhraseManager.STOP_NOT_FUNCTIONAL_STORY.startsWith("STOP:"));
        assertTrue(StopPhraseManager.STOP_REPOSITORY_NOT_IN_SCOPE.startsWith("STOP:"));
    }
    
    @Test
    void testProcessingStopPhrases_HaveCorrectFormat() {
        assertTrue(StopPhraseManager.STOP_INTENT_CANNOT_BE_INFERRED.startsWith("STOP:"));
        assertTrue(StopPhraseManager.STOP_STATE_MODEL_UNDEFINED.startsWith("STOP:"));
        assertTrue(StopPhraseManager.STOP_AUDIT_CONTRACT_UNKNOWN.startsWith("STOP:"));
        assertTrue(StopPhraseManager.STOP_PERMISSION_MODEL_UNCLEAR.startsWith("STOP:"));
        assertTrue(StopPhraseManager.STOP_DATA_FIELDS_AMBIGUOUS.startsWith("STOP:"));
    }
    
    @Test
    void testLoopDetectionStopPhrases_HaveCorrectFormat() {
        assertTrue(StopPhraseManager.STOP_REWRITING_WITHOUT_NEW_INFO.startsWith("STOP:"));
        assertTrue(StopPhraseManager.STOP_ACCEPTANCE_CRITERIA_EXCEED_SCOPE.startsWith("STOP:"));
        assertTrue(StopPhraseManager.STOP_EXCESSIVE_AMBIGUITY.startsWith("STOP:"));
    }
    
    @Test
    void testUnsafeInferenceStopPhrases_HaveCorrectFormat() {
        assertTrue(StopPhraseManager.STOP_UNSAFE_INFERENCE_LEGAL.startsWith("STOP:"));
        assertTrue(StopPhraseManager.STOP_UNSAFE_INFERENCE_FINANCIAL.startsWith("STOP:"));
        assertTrue(StopPhraseManager.STOP_UNSAFE_INFERENCE_SECURITY.startsWith("STOP:"));
        assertTrue(StopPhraseManager.STOP_UNSAFE_INFERENCE_REGULATORY.startsWith("STOP:"));
    }
    
    @Test
    void testAllStopPhrases_AreUnique() {
        // Ensure no duplicate stop phrases
        String[] allStopPhrases = {
            StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED,
            StopPhraseManager.STOP_NOT_FUNCTIONAL_STORY,
            StopPhraseManager.STOP_REPOSITORY_NOT_IN_SCOPE,
            StopPhraseManager.STOP_INTENT_CANNOT_BE_INFERRED,
            StopPhraseManager.STOP_STATE_MODEL_UNDEFINED,
            StopPhraseManager.STOP_AUDIT_CONTRACT_UNKNOWN,
            StopPhraseManager.STOP_PERMISSION_MODEL_UNCLEAR,
            StopPhraseManager.STOP_DATA_FIELDS_AMBIGUOUS,
            StopPhraseManager.STOP_REWRITING_WITHOUT_NEW_INFO,
            StopPhraseManager.STOP_ACCEPTANCE_CRITERIA_EXCEED_SCOPE,
            StopPhraseManager.STOP_EXCESSIVE_AMBIGUITY,
            StopPhraseManager.STOP_UNSAFE_INFERENCE_LEGAL,
            StopPhraseManager.STOP_UNSAFE_INFERENCE_FINANCIAL,
            StopPhraseManager.STOP_UNSAFE_INFERENCE_SECURITY,
            StopPhraseManager.STOP_UNSAFE_INFERENCE_REGULATORY
        };
        
        for (int i = 0; i < allStopPhrases.length; i++) {
            for (int j = i + 1; j < allStopPhrases.length; j++) {
                assertNotEquals(allStopPhrases[i], allStopPhrases[j],
                    "Stop phrases must be unique");
            }
        }
    }
    
    // ========== isStopPhrase Tests ==========
    
    @Test
    void testIsStopPhrase_ValidStopPhrase() {
        assertTrue(StopPhraseManager.isStopPhrase("STOP: Some reason"));
        assertTrue(StopPhraseManager.isStopPhrase(StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED));
        assertTrue(StopPhraseManager.isStopPhrase(StopPhraseManager.STOP_EXCESSIVE_AMBIGUITY));
    }
    
    @Test
    void testIsStopPhrase_InvalidStopPhrase() {
        assertFalse(StopPhraseManager.isStopPhrase("Not a stop phrase"));
        assertFalse(StopPhraseManager.isStopPhrase("Stop: lowercase"));
        assertFalse(StopPhraseManager.isStopPhrase("STOP without colon"));
    }
    
    @Test
    void testIsStopPhrase_NullMessage() {
        assertFalse(StopPhraseManager.isStopPhrase(null));
    }
    
    @Test
    void testIsStopPhrase_EmptyMessage() {
        assertFalse(StopPhraseManager.isStopPhrase(""));
    }
    
    // ========== extractReason Tests ==========
    
    @Test
    void testExtractReason_ValidStopPhrase() {
        assertEquals("Some reason", 
            StopPhraseManager.extractReason("STOP: Some reason"));
        assertEquals("Issue prefix not supported", 
            StopPhraseManager.extractReason(StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED));
    }
    
    @Test
    void testExtractReason_NonStopPhrase() {
        assertEquals("Not a stop phrase", 
            StopPhraseManager.extractReason("Not a stop phrase"));
    }
    
    @Test
    void testExtractReason_NullMessage() {
        assertNull(StopPhraseManager.extractReason(null));
    }
    
    @Test
    void testExtractReason_EmptyMessage() {
        assertEquals("", StopPhraseManager.extractReason(""));
    }
    
    // ========== withContext Tests ==========
    
    @Test
    void testWithContext_AddsContext() {
        String result = StopPhraseManager.withContext(
            StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED,
            "Missing [BACKEND] prefix"
        );
        
        assertTrue(result.contains(StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED));
        assertTrue(result.contains("Missing [BACKEND] prefix"));
        assertTrue(result.contains("("));
        assertTrue(result.contains(")"));
    }
    
    @Test
    void testWithContext_NullContext() {
        String result = StopPhraseManager.withContext(
            StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED,
            null
        );
        
        assertEquals(StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED, result);
    }
    
    @Test
    void testWithContext_EmptyContext() {
        String result = StopPhraseManager.withContext(
            StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED,
            ""
        );
        
        assertEquals(StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED, result);
    }
    
    @Test
    void testWithContext_WhitespaceContext() {
        String result = StopPhraseManager.withContext(
            StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED,
            "   "
        );
        
        assertEquals(StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED, result);
    }
    
    // ========== categorize Tests ==========
    
    @Test
    void testCategorize_ValidationStopPhrases() {
        assertEquals(StopPhraseManager.StopPhraseCategory.VALIDATION,
            StopPhraseManager.categorize(StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED));
        assertEquals(StopPhraseManager.StopPhraseCategory.VALIDATION,
            StopPhraseManager.categorize(StopPhraseManager.STOP_NOT_FUNCTIONAL_STORY));
        assertEquals(StopPhraseManager.StopPhraseCategory.VALIDATION,
            StopPhraseManager.categorize(StopPhraseManager.STOP_REPOSITORY_NOT_IN_SCOPE));
    }
    
    @Test
    void testCategorize_ProcessingStopPhrases() {
        assertEquals(StopPhraseManager.StopPhraseCategory.PROCESSING,
            StopPhraseManager.categorize(StopPhraseManager.STOP_INTENT_CANNOT_BE_INFERRED));
        assertEquals(StopPhraseManager.StopPhraseCategory.PROCESSING,
            StopPhraseManager.categorize(StopPhraseManager.STOP_STATE_MODEL_UNDEFINED));
        assertEquals(StopPhraseManager.StopPhraseCategory.PROCESSING,
            StopPhraseManager.categorize(StopPhraseManager.STOP_AUDIT_CONTRACT_UNKNOWN));
        assertEquals(StopPhraseManager.StopPhraseCategory.PROCESSING,
            StopPhraseManager.categorize(StopPhraseManager.STOP_PERMISSION_MODEL_UNCLEAR));
        assertEquals(StopPhraseManager.StopPhraseCategory.PROCESSING,
            StopPhraseManager.categorize(StopPhraseManager.STOP_DATA_FIELDS_AMBIGUOUS));
    }
    
    @Test
    void testCategorize_LoopDetectionStopPhrases() {
        assertEquals(StopPhraseManager.StopPhraseCategory.LOOP_DETECTION,
            StopPhraseManager.categorize(StopPhraseManager.STOP_REWRITING_WITHOUT_NEW_INFO));
        assertEquals(StopPhraseManager.StopPhraseCategory.LOOP_DETECTION,
            StopPhraseManager.categorize(StopPhraseManager.STOP_ACCEPTANCE_CRITERIA_EXCEED_SCOPE));
        assertEquals(StopPhraseManager.StopPhraseCategory.LOOP_DETECTION,
            StopPhraseManager.categorize(StopPhraseManager.STOP_EXCESSIVE_AMBIGUITY));
    }
    
    @Test
    void testCategorize_UnsafeInferenceStopPhrases() {
        assertEquals(StopPhraseManager.StopPhraseCategory.UNSAFE_INFERENCE,
            StopPhraseManager.categorize(StopPhraseManager.STOP_UNSAFE_INFERENCE_LEGAL));
        assertEquals(StopPhraseManager.StopPhraseCategory.UNSAFE_INFERENCE,
            StopPhraseManager.categorize(StopPhraseManager.STOP_UNSAFE_INFERENCE_FINANCIAL));
        assertEquals(StopPhraseManager.StopPhraseCategory.UNSAFE_INFERENCE,
            StopPhraseManager.categorize(StopPhraseManager.STOP_UNSAFE_INFERENCE_SECURITY));
        assertEquals(StopPhraseManager.StopPhraseCategory.UNSAFE_INFERENCE,
            StopPhraseManager.categorize(StopPhraseManager.STOP_UNSAFE_INFERENCE_REGULATORY));
    }
    
    @Test
    void testCategorize_ContextualizedStopPhrase() {
        String contextualized = StopPhraseManager.withContext(
            StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED,
            "Missing [BACKEND]"
        );
        
        assertEquals(StopPhraseManager.StopPhraseCategory.VALIDATION,
            StopPhraseManager.categorize(contextualized));
    }
    
    @Test
    void testCategorize_UnknownStopPhrase() {
        assertNull(StopPhraseManager.categorize("STOP: Unknown reason"));
    }
    
    @Test
    void testCategorize_NullStopPhrase() {
        assertNull(StopPhraseManager.categorize(null));
    }
    
    @Test
    void testCategorize_NonStopPhrase() {
        assertNull(StopPhraseManager.categorize("Not a stop phrase"));
    }
    
    // ========== shouldProduceRewrite Tests ==========
    
    @Test
    void testShouldProduceRewrite_StateModelUndefined() {
        assertTrue(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_STATE_MODEL_UNDEFINED));
    }
    
    @Test
    void testShouldProduceRewrite_AuditContractUnknown() {
        assertTrue(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_AUDIT_CONTRACT_UNKNOWN));
    }
    
    @Test
    void testShouldProduceRewrite_PermissionModelUnclear() {
        assertTrue(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_PERMISSION_MODEL_UNCLEAR));
    }
    
    @Test
    void testShouldProduceRewrite_DataFieldsAmbiguous() {
        assertTrue(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_DATA_FIELDS_AMBIGUOUS));
    }
    
    @Test
    void testShouldProduceRewrite_ContextualizedRewriteStopPhrase() {
        String contextualized = StopPhraseManager.withContext(
            StopPhraseManager.STOP_STATE_MODEL_UNDEFINED,
            "Additional context"
        );
        
        assertTrue(StopPhraseManager.shouldProduceRewrite(contextualized));
    }
    
    @Test
    void testShouldProduceRewrite_ValidationStopPhrase() {
        assertFalse(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED));
        assertFalse(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_NOT_FUNCTIONAL_STORY));
        assertFalse(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_REPOSITORY_NOT_IN_SCOPE));
    }
    
    @Test
    void testShouldProduceRewrite_LoopDetectionStopPhrase() {
        assertFalse(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_REWRITING_WITHOUT_NEW_INFO));
        assertFalse(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_ACCEPTANCE_CRITERIA_EXCEED_SCOPE));
        assertFalse(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_EXCESSIVE_AMBIGUITY));
    }
    
    @Test
    void testShouldProduceRewrite_UnsafeInferenceStopPhrase() {
        assertFalse(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_UNSAFE_INFERENCE_LEGAL));
        assertFalse(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_UNSAFE_INFERENCE_FINANCIAL));
        assertFalse(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_UNSAFE_INFERENCE_SECURITY));
        assertFalse(StopPhraseManager.shouldProduceRewrite(
            StopPhraseManager.STOP_UNSAFE_INFERENCE_REGULATORY));
    }
    
    @Test
    void testShouldProduceRewrite_NullStopPhrase() {
        assertFalse(StopPhraseManager.shouldProduceRewrite(null));
    }
    
    @Test
    void testShouldProduceRewrite_UnknownStopPhrase() {
        assertFalse(StopPhraseManager.shouldProduceRewrite("STOP: Unknown reason"));
    }
    
    // ========== Constructor Tests ==========
    
    @Test
    void testConstructor_ThrowsException() {
        assertThrows(UnsupportedOperationException.class, () -> {
            // Use reflection to try to instantiate
            java.lang.reflect.Constructor<StopPhraseManager> constructor = 
                StopPhraseManager.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            try {
                constructor.newInstance();
            } catch (java.lang.reflect.InvocationTargetException e) {
                // Unwrap the InvocationTargetException to get the actual exception
                throw e.getCause();
            }
        });
    }
    
    // ========== Integration Tests ==========
    
    @Test
    void testFullWorkflow_ValidationFailure() {
        String stopPhrase = StopPhraseManager.STOP_PREFIX_NOT_SUPPORTED;
        
        // Verify it's a stop phrase
        assertTrue(StopPhraseManager.isStopPhrase(stopPhrase));
        
        // Verify category
        assertEquals(StopPhraseManager.StopPhraseCategory.VALIDATION,
            StopPhraseManager.categorize(stopPhrase));
        
        // Verify it doesn't produce a rewrite
        assertFalse(StopPhraseManager.shouldProduceRewrite(stopPhrase));
        
        // Extract reason
        String reason = StopPhraseManager.extractReason(stopPhrase);
        assertEquals("Issue prefix not supported", reason);
    }
    
    @Test
    void testFullWorkflow_ProcessingFailureWithRewrite() {
        String stopPhrase = StopPhraseManager.STOP_STATE_MODEL_UNDEFINED;
        String context = "User roles not defined";
        String contextualizedStopPhrase = StopPhraseManager.withContext(stopPhrase, context);
        
        // Verify it's a stop phrase
        assertTrue(StopPhraseManager.isStopPhrase(contextualizedStopPhrase));
        
        // Verify category
        assertEquals(StopPhraseManager.StopPhraseCategory.PROCESSING,
            StopPhraseManager.categorize(contextualizedStopPhrase));
        
        // Verify it produces a rewrite
        assertTrue(StopPhraseManager.shouldProduceRewrite(contextualizedStopPhrase));
        
        // Verify context is included
        assertTrue(contextualizedStopPhrase.contains(context));
    }
    
    @Test
    void testFullWorkflow_LoopDetection() {
        String stopPhrase = StopPhraseManager.STOP_EXCESSIVE_AMBIGUITY;
        String context = "11 questions, max 10";
        String contextualizedStopPhrase = StopPhraseManager.withContext(stopPhrase, context);
        
        // Verify it's a stop phrase
        assertTrue(StopPhraseManager.isStopPhrase(contextualizedStopPhrase));
        
        // Verify category
        assertEquals(StopPhraseManager.StopPhraseCategory.LOOP_DETECTION,
            StopPhraseManager.categorize(contextualizedStopPhrase));
        
        // Verify it doesn't produce a rewrite
        assertFalse(StopPhraseManager.shouldProduceRewrite(contextualizedStopPhrase));
        
        // Verify context is included
        assertTrue(contextualizedStopPhrase.contains(context));
    }
}
