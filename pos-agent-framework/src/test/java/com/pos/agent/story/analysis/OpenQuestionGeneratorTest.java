package com.pos.agent.story.analysis;

import com.pos.agent.story.models.OpenQuestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpenQuestionGenerator.
 * 
 * Tests open question generation including:
 * - Question text generation
 * - Impact description generation
 * - Ambiguity categorization
 * - Structure validation
 * 
 * Requirements: 8.6, 8.7
 */
class OpenQuestionGeneratorTest {
    
    private OpenQuestionGenerator generator;
    
    @BeforeEach
    void setUp() {
        generator = new OpenQuestionGenerator();
    }
    
    // ========== Question Text Generation Tests ==========
    
    @Test
    void testGenerateQuestionText_RequiredFields() {
        String ambiguity = "Required fields are unclear";
        OpenQuestion question = generator.createOpenQuestion(
            ambiguity,
            OpenQuestionGenerator.AmbiguityType.REQUIRED_FIELDS
        );
        
        assertNotNull(question.getQuestion());
        assertTrue(question.getQuestion().contains("?") || question.getQuestion().contains("field"),
                  "Question should be about fields");
    }
    
    @Test
    void testGenerateQuestionText_PermissionRules() {
        String ambiguity = "Permission rules are unspecified";
        OpenQuestion question = generator.createOpenQuestion(
            ambiguity,
            OpenQuestionGenerator.AmbiguityType.PERMISSION_RULES
        );
        
        assertNotNull(question.getQuestion());
        assertTrue(question.getQuestion().toLowerCase().contains("permission") ||
                   question.getQuestion().toLowerCase().contains("access"),
                  "Question should be about permissions");
    }
    
    @Test
    void testGenerateQuestionText_StateTransitions() {
        String ambiguity = "State transitions are incomplete";
        OpenQuestion question = generator.createOpenQuestion(
            ambiguity,
            OpenQuestionGenerator.AmbiguityType.STATE_TRANSITIONS
        );
        
        assertNotNull(question.getQuestion());
        assertTrue(question.getQuestion().toLowerCase().contains("state") ||
                   question.getQuestion().toLowerCase().contains("transition"),
                  "Question should be about state transitions");
    }
    
    @Test
    void testGenerateQuestionText_AuditContracts() {
        String ambiguity = "Audit contract is unknown";
        OpenQuestion question = generator.createOpenQuestion(
            ambiguity,
            OpenQuestionGenerator.AmbiguityType.AUDIT_CONTRACTS
        );
        
        assertNotNull(question.getQuestion());
        assertTrue(question.getQuestion().toLowerCase().contains("audit") ||
                   question.getQuestion().toLowerCase().contains("event"),
                  "Question should be about audit contracts");
    }
    
    @Test
    void testGenerateQuestionText_UniquenessIdempotency() {
        String ambiguity = "Uniqueness constraints are ambiguous";
        OpenQuestion question = generator.createOpenQuestion(
            ambiguity,
            OpenQuestionGenerator.AmbiguityType.UNIQUENESS_IDEMPOTENCY
        );
        
        assertNotNull(question.getQuestion());
        assertTrue(question.getQuestion().toLowerCase().contains("unique") ||
                   question.getQuestion().toLowerCase().contains("idempotency"),
                  "Question should be about uniqueness or idempotency");
    }
    
    @Test
    void testGenerateQuestionText_AlreadyQuestion() {
        String ambiguity = "What are the required fields?";
        OpenQuestion question = generator.createOpenQuestion(
            ambiguity,
            OpenQuestionGenerator.AmbiguityType.REQUIRED_FIELDS
        );
        
        // If ambiguity is already a question, it should be preserved
        assertTrue(question.getQuestion().contains("?"),
                  "Question mark should be preserved");
    }
    
    // ========== Impact Description Tests ==========
    
    @Test
    void testGenerateImpactDescription_RequiredFields() {
        OpenQuestion question = generator.createOpenQuestion(
            "Test ambiguity",
            OpenQuestionGenerator.AmbiguityType.REQUIRED_FIELDS
        );
        
        String impact = question.getImpact();
        assertNotNull(impact);
        assertFalse(impact.isEmpty());
        assertTrue(impact.toLowerCase().contains("data") || impact.toLowerCase().contains("validation"),
                  "Impact should mention data or validation");
    }
    
    @Test
    void testGenerateImpactDescription_PermissionRules() {
        OpenQuestion question = generator.createOpenQuestion(
            "Test ambiguity",
            OpenQuestionGenerator.AmbiguityType.PERMISSION_RULES
        );
        
        String impact = question.getImpact();
        assertNotNull(impact);
        assertTrue(impact.toLowerCase().contains("security") || impact.toLowerCase().contains("access"),
                  "Impact should mention security or access");
    }
    
    @Test
    void testGenerateImpactDescription_StateTransitions() {
        OpenQuestion question = generator.createOpenQuestion(
            "Test ambiguity",
            OpenQuestionGenerator.AmbiguityType.STATE_TRANSITIONS
        );
        
        String impact = question.getImpact();
        assertNotNull(impact);
        assertTrue(impact.toLowerCase().contains("state") || impact.toLowerCase().contains("invalid"),
                  "Impact should mention state or invalid operations");
    }
    
    // ========== Ambiguity Categorization Tests ==========
    
    @Test
    void testCategorizeAmbiguity_RequiredFields() {
        String ambiguity = "The required fields are unclear";
        OpenQuestionGenerator.AmbiguityType type = generator.categorizeAmbiguity(ambiguity);
        assertEquals(OpenQuestionGenerator.AmbiguityType.REQUIRED_FIELDS, type);
    }
    
    @Test
    void testCategorizeAmbiguity_PermissionRules() {
        String ambiguity = "Permission rules are unspecified";
        OpenQuestionGenerator.AmbiguityType type = generator.categorizeAmbiguity(ambiguity);
        assertEquals(OpenQuestionGenerator.AmbiguityType.PERMISSION_RULES, type);
    }
    
    @Test
    void testCategorizeAmbiguity_StateTransitions() {
        String ambiguity = "State transitions are incomplete";
        OpenQuestionGenerator.AmbiguityType type = generator.categorizeAmbiguity(ambiguity);
        assertEquals(OpenQuestionGenerator.AmbiguityType.STATE_TRANSITIONS, type);
    }
    
    @Test
    void testCategorizeAmbiguity_AuditContracts() {
        String ambiguity = "Audit contract is unknown";
        OpenQuestionGenerator.AmbiguityType type = generator.categorizeAmbiguity(ambiguity);
        assertEquals(OpenQuestionGenerator.AmbiguityType.AUDIT_CONTRACTS, type);
    }
    
    @Test
    void testCategorizeAmbiguity_UniquenessIdempotency() {
        String ambiguity = "Uniqueness constraints are ambiguous";
        OpenQuestionGenerator.AmbiguityType type = generator.categorizeAmbiguity(ambiguity);
        assertEquals(OpenQuestionGenerator.AmbiguityType.UNIQUENESS_IDEMPOTENCY, type);
    }
    
    @Test
    void testCategorizeAmbiguity_DefaultToBusinessRules() {
        String ambiguity = "Something is unclear";
        OpenQuestionGenerator.AmbiguityType type = generator.categorizeAmbiguity(ambiguity);
        assertEquals(OpenQuestionGenerator.AmbiguityType.BUSINESS_RULES, type,
                    "Should default to BUSINESS_RULES when no pattern matches");
    }
    
    // ========== Structure Validation Tests ==========
    
    @Test
    void testValidateOpenQuestion_Valid() {
        OpenQuestion question = new OpenQuestion(
            "What are the required fields?",
            "Required fields determine data completeness",
            "Without clear field requirements, implementation may be incomplete"
        );
        
        assertTrue(generator.validateOpenQuestion(question));
    }
    
    @Test
    void testValidateOpenQuestion_NullQuestion() {
        assertFalse(generator.validateOpenQuestion(null));
    }
    
    @Test
    void testValidateOpenQuestion_EmptyQuestionText() {
        OpenQuestion question = new OpenQuestion(
            "",
            "Why it matters",
            "Impact description"
        );
        
        assertFalse(generator.validateOpenQuestion(question));
    }
    
    @Test
    void testValidateOpenQuestion_WhitespaceQuestionText() {
        OpenQuestion question = new OpenQuestion(
            "   ",
            "Why it matters",
            "Impact description"
        );
        
        assertFalse(generator.validateOpenQuestion(question));
    }
    
    // ========== Batch Generation Tests ==========
    
    @Test
    void testGenerateOpenQuestions_MultipleAmbiguities() {
        List<String> ambiguities = List.of(
            "Required fields are unclear",
            "Permission rules are unspecified",
            "State transitions are incomplete"
        );
        
        List<OpenQuestion> questions = generator.generateOpenQuestions(ambiguities);
        
        assertEquals(3, questions.size());
        for (OpenQuestion question : questions) {
            assertTrue(generator.validateOpenQuestion(question));
        }
    }
    
    @Test
    void testGenerateOpenQuestions_EmptyList() {
        List<OpenQuestion> questions = generator.generateOpenQuestions(List.of());
        assertTrue(questions.isEmpty());
    }
    
    @Test
    void testGenerateOpenQuestions_NullList() {
        List<OpenQuestion> questions = generator.generateOpenQuestions(null);
        assertTrue(questions.isEmpty());
    }
    
    @Test
    void testGenerateOpenQuestions_SkipsNullEntries() {
        List<String> ambiguities = Arrays.asList(
            "Required fields are unclear",
            null,
            "Permission rules are unspecified"
        );
        
        List<OpenQuestion> questions = generator.generateOpenQuestions(ambiguities);
        
        assertEquals(2, questions.size());
    }
    
    @Test
    void testGenerateOpenQuestions_SkipsEmptyEntries() {
        List<String> ambiguities = Arrays.asList(
            "Required fields are unclear",
            "",
            "   ",
            "Permission rules are unspecified"
        );
        
        List<OpenQuestion> questions = generator.generateOpenQuestions(ambiguities);
        
        assertEquals(2, questions.size());
    }
    
    // ========== Formatting Tests ==========
    
    @Test
    void testFormatOpenQuestion() {
        OpenQuestion question = new OpenQuestion(
            "What are the required fields?",
            "Required fields determine data completeness",
            "Without clear field requirements, implementation may be incomplete"
        );
        
        String formatted = generator.formatOpenQuestion(question);
        
        assertNotNull(formatted);
        assertTrue(formatted.contains("**Question:**"));
        assertTrue(formatted.contains("**Why it matters:**"));
        assertTrue(formatted.contains("**Impact:**"));
        assertTrue(formatted.contains(question.getQuestion()));
        assertTrue(formatted.contains(question.getWhyItMatters()));
        assertTrue(formatted.contains(question.getImpact()));
    }
    
    @Test
    void testFormatOpenQuestion_ContainsNewlines() {
        OpenQuestion question = new OpenQuestion(
            "What are the required fields?",
            "Required fields determine data completeness",
            "Without clear field requirements, implementation may be incomplete"
        );
        
        String formatted = generator.formatOpenQuestion(question);
        
        // Should have newlines between sections
        assertTrue(formatted.contains("\n"));
    }
    
    // ========== Edge Cases ==========
    
    @Test
    void testCreateOpenQuestion_LongAmbiguity() {
        String longAmbiguity = "This is a very long ambiguity description that contains many words and " +
                              "describes a complex situation with multiple unclear aspects that need " +
                              "clarification from the stakeholders before implementation can proceed";
        
        OpenQuestion question = generator.createOpenQuestion(
            longAmbiguity,
            OpenQuestionGenerator.AmbiguityType.BUSINESS_RULES
        );
        
        assertTrue(generator.validateOpenQuestion(question));
    }
    
    @Test
    void testCreateOpenQuestion_SpecialCharacters() {
        String ambiguity = "Field validation: @#$%^&*() characters are unclear";
        
        OpenQuestion question = generator.createOpenQuestion(
            ambiguity,
            OpenQuestionGenerator.AmbiguityType.REQUIRED_FIELDS
        );
        
        assertTrue(generator.validateOpenQuestion(question));
    }
    
    @Test
    void testCreateOpenQuestion_AllAmbiguityTypes() {
        for (OpenQuestionGenerator.AmbiguityType type : OpenQuestionGenerator.AmbiguityType.values()) {
            OpenQuestion question = generator.createOpenQuestion(
                "Test ambiguity for " + type,
                type
            );
            
            assertTrue(generator.validateOpenQuestion(question),
                      "Should generate valid question for type: " + type);
        }
    }
}
