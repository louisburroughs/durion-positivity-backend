package com.pos.agent.story.config;

import com.pos.agent.story.models.*;
import com.pos.agent.story.loop.LoopDetectionResult;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Comprehensive logging system for the Story Strengthening Agent.
 * 
 * Provides detailed logging for all validation, parsing, analysis, transformation,
 * and loop detection operations with context for troubleshooting and audit history tracking.
 * 
 * Follows the reference pattern from workspace-agents/src/main/java/com/durion/audit/AuditLogger.java
 * 
 * Requirements: Task 15.1 - Observability and Logging
 */
public class StoryLogger {
    
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final String sessionId;
    private LocalDateTime sessionStartTime;
    private long validationStartTime;
    private long parsingStartTime;
    private long analysisStartTime;
    private long transformationStartTime;
    private long outputGenerationStartTime;
    
    public StoryLogger(String sessionId) {
        this.sessionId = sessionId;
        this.sessionStartTime = LocalDateTime.now();
        logSessionStart();
    }
    
    /**
     * Logs the start of a story strengthening session.
     */
    private void logSessionStart() {
        System.out.println("🚀 ===== STORY STRENGTHENING SESSION STARTED =====");
        System.out.println("📅 Session ID: " + sessionId);
        System.out.println("⏰ Start Time: " + sessionStartTime.format(TIMESTAMP_FORMAT));
        System.out.println("🔍 Agent: Story Strengthening Agent");
        System.out.println("==================================================");
    }
    
    /**
     * Logs the start of validation phase.
     */
    public void logValidationStart(GitHubIssue issue) {
        validationStartTime = System.currentTimeMillis();
        System.out.println("\n🔍 VALIDATION PHASE STARTED");
        System.out.println("├─ Issue Title: " + issue.getTitle());
        System.out.println("├─ Repository: " + issue.getRepository());
        System.out.println("├─ Labels: " + String.join(", ", issue.getLabels()));
        System.out.println("└─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs validation success.
     */
    public void logValidationSuccess(long durationMs) {
        System.out.println("✅ VALIDATION PHASE COMPLETED");
        System.out.println("├─ Status: SUCCESS");
        System.out.println("├─ Duration: " + durationMs + "ms");
        System.out.println("└─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs validation failure with stop phrase.
     */
    public void logValidationFailure(ValidationResult result, long durationMs) {
        System.out.println("❌ VALIDATION PHASE FAILED");
        System.out.println("├─ Status: FAILURE");
        System.out.println("├─ Duration: " + durationMs + "ms");
        System.out.println("├─ Stop Phrase: " + result.getStopPhrase().orElse("Unknown"));
        System.out.println("├─ Reason: " + result.getReason().orElse("Unknown"));
        System.out.println("└─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs the start of parsing phase.
     */
    public void logParsingStart() {
        parsingStartTime = System.currentTimeMillis();
        System.out.println("\n📄 PARSING PHASE STARTED");
        System.out.println("└─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs parsing completion.
     */
    public void logParsingComplete(ParsedIssue parsedIssue, long durationMs) {
        System.out.println("✅ PARSING PHASE COMPLETED");
        System.out.println("├─ Status: SUCCESS");
        System.out.println("├─ Duration: " + durationMs + "ms");
        System.out.println("├─ Sections Parsed: " + parsedIssue.getSections().size());
        System.out.println("└─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs the start of analysis phase.
     */
    public void logAnalysisStart() {
        analysisStartTime = System.currentTimeMillis();
        System.out.println("\n🔬 ANALYSIS PHASE STARTED");
        System.out.println("└─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs analysis completion with ambiguity detection.
     */
    public void logAnalysisComplete(AnalysisResult analysisResult, long durationMs) {
        System.out.println("✅ ANALYSIS PHASE COMPLETED");
        System.out.println("├─ Status: SUCCESS");
        System.out.println("├─ Duration: " + durationMs + "ms");
        System.out.println("├─ Actors Identified: " + analysisResult.getActors().size());
        System.out.println("├─ Functional Requirements: " + analysisResult.getFunctionalRequirements().size());
        System.out.println("├─ Business Rules: " + analysisResult.getBusinessRules().size());
        System.out.println("├─ Data Requirements: " + analysisResult.getDataRequirements().size());
        System.out.println("├─ Ambiguities Detected: " + analysisResult.getAmbiguities().size());
        
        if (!analysisResult.getAmbiguities().isEmpty()) {
            System.out.println("├─ Ambiguity Types:");
            analysisResult.getAmbiguities().stream()
                .limit(5)
                .forEach(ambiguity -> System.out.println("│  • " + ambiguity.getQuestion()));
            if (analysisResult.getAmbiguities().size() > 5) {
                System.out.println("│  ... and " + (analysisResult.getAmbiguities().size() - 5) + " more");
            }
        }
        
        System.out.println("└─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs ambiguity detection details.
     */
    public void logAmbiguityDetection(List<OpenQuestion> ambiguities) {
        if (ambiguities.isEmpty()) {
            System.out.println("\nℹ️ NO AMBIGUITIES DETECTED");
            return;
        }
        
        System.out.println("\n⚠️ AMBIGUITIES DETECTED: " + ambiguities.size());
        System.out.println("├─ Ambiguity Details:");
        ambiguities.forEach(ambiguity -> {
            System.out.println("│  ┌─ Question: " + ambiguity.getQuestion());
            System.out.println("│  └─ Impact: " + ambiguity.getWhyItMatters());
        });
        System.out.println("└─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs the start of transformation phase.
     */
    public void logTransformationStart() {
        transformationStartTime = System.currentTimeMillis();
        System.out.println("\n🔄 TRANSFORMATION PHASE STARTED");
        System.out.println("└─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs transformation completion.
     */
    public void logTransformationComplete(TransformedRequirements transformedRequirements, long durationMs) {
        System.out.println("✅ TRANSFORMATION PHASE COMPLETED");
        System.out.println("├─ Status: SUCCESS");
        System.out.println("├─ Duration: " + durationMs + "ms");
        System.out.println("├─ Functional Requirements: " + transformedRequirements.getFunctionalRequirements().size());
        System.out.println("├─ Gherkin Scenarios: " + transformedRequirements.getAcceptanceCriteria().size());
        System.out.println("├─ Open Questions: " + transformedRequirements.getOpenQuestions().size());
        System.out.println("└─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs the start of output generation phase.
     */
    public void logOutputGenerationStart() {
        outputGenerationStartTime = System.currentTimeMillis();
        System.out.println("\n📝 OUTPUT GENERATION PHASE STARTED");
        System.out.println("└─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs output generation completion.
     */
    public void logOutputGenerationComplete(int outputLength, long durationMs) {
        System.out.println("✅ OUTPUT GENERATION PHASE COMPLETED");
        System.out.println("├─ Status: SUCCESS");
        System.out.println("├─ Duration: " + durationMs + "ms");
        System.out.println("├─ Output Length: " + outputLength + " characters");
        System.out.println("└─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs loop detection check.
     */
    public void logLoopDetectionCheck(String phase, LoopDetectionResult result) {
        System.out.println("\n🔁 LOOP DETECTION CHECK: " + phase);
        System.out.println("├─ Loop Detected: " + result.isLoopDetected());
        
        if (result.isLoopDetected()) {
            System.out.println("├─ Stop Phrase: " + result.getStopPhrase().orElse("Unknown"));
            System.out.println("├─ Details: " + result.getDetails().orElse("Unknown"));
            System.out.println("└─ ⚠️ PROCESSING HALTED DUE TO LOOP CONDITION");
        } else {
            System.out.println("└─ ✅ No loop detected, continuing processing");
        }
        
        System.out.println("   Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs open questions count monitoring.
     */
    public void logOpenQuestionsCount(int count, int threshold) {
        System.out.println("\n📊 OPEN QUESTIONS MONITORING");
        System.out.println("├─ Current Count: " + count);
        System.out.println("├─ Threshold: " + threshold);
        
        if (count >= threshold) {
            System.out.println("└─ ⚠️ WARNING: Open questions at or above threshold");
        } else {
            double percentage = (count * 100.0) / threshold;
            System.out.println("└─ ℹ️ Open questions at " + String.format("%.1f%%", percentage) + " of threshold");
        }
        
        System.out.println("   Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
    }
    
    /**
     * Logs processing time summary for all stages.
     */
    public void logProcessingTimeSummary(
            long validationMs,
            long parsingMs,
            long analysisMs,
            long transformationMs,
            long outputGenerationMs) {
        long totalMs = validationMs + parsingMs + analysisMs + transformationMs + outputGenerationMs;
        
        System.out.println("\n⏱️ PROCESSING TIME SUMMARY");
        System.out.println("├─ Validation: " + validationMs + "ms (" + percentage(validationMs, totalMs) + "%)");
        System.out.println("├─ Parsing: " + parsingMs + "ms (" + percentage(parsingMs, totalMs) + "%)");
        System.out.println("├─ Analysis: " + analysisMs + "ms (" + percentage(analysisMs, totalMs) + "%)");
        System.out.println("├─ Transformation: " + transformationMs + "ms (" + percentage(transformationMs, totalMs) + "%)");
        System.out.println("├─ Output Generation: " + outputGenerationMs + "ms (" + percentage(outputGenerationMs, totalMs) + "%)");
        System.out.println("└─ Total: " + totalMs + "ms");
    }
    
    /**
     * Logs error details with context.
     */
    public void logError(String phase, Exception error, String context) {
        System.out.println("\n❌ ERROR IN " + phase.toUpperCase());
        System.out.println("├─ Error Type: " + error.getClass().getSimpleName());
        System.out.println("├─ Error Message: " + error.getMessage());
        System.out.println("├─ Context: " + context);
        System.out.println("├─ Timestamp: " + LocalDateTime.now().format(TIMESTAMP_FORMAT));
        System.out.println("└─ Session: " + sessionId);
        
        if (error.getCause() != null) {
            System.out.println("   Caused by: " + error.getCause().getClass().getSimpleName() + 
                             ": " + error.getCause().getMessage());
        }
    }
    
    /**
     * Logs the end of the story strengthening session.
     */
    public void logSessionEnd(boolean success, String finalStatus) {
        LocalDateTime endTime = LocalDateTime.now();
        long durationSeconds = java.time.Duration.between(sessionStartTime, endTime).toSeconds();
        
        System.out.println("\n🏁 ===== STORY STRENGTHENING SESSION COMPLETED =====");
        System.out.println("📅 Session ID: " + sessionId);
        System.out.println("⏰ End Time: " + endTime.format(TIMESTAMP_FORMAT));
        System.out.println("⏱️ Total Duration: " + durationSeconds + " seconds");
        System.out.println("📊 Final Status: " + finalStatus);
        System.out.println("✅ Success: " + success);
        System.out.println("====================================================");
    }
    
    /**
     * Calculates percentage for time distribution.
     */
    private String percentage(long part, long total) {
        if (total == 0) return "0.0";
        return String.format("%.1f", (part * 100.0) / total);
    }
    
    /**
     * Gets the session ID.
     */
    public String getSessionId() {
        return sessionId;
    }
    
    /**
     * Gets the session start time.
     */
    public LocalDateTime getSessionStartTime() {
        return sessionStartTime;
    }
}
