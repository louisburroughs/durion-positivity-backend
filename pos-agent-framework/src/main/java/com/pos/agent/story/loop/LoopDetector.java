package com.pos.agent.story.loop;

/**
 * Interface for detecting and preventing processing loops.
 * Tracks rewrite iterations, counts acceptance criteria and open questions,
 * and detects unsafe inference attempts.
 * 
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
 */
public interface LoopDetector {
    
    /**
     * Checks for loop conditions in the processing context.
     * Tracks rewrite iterations (max 2 per section),
     * counts acceptance criteria (threshold: 25 scenarios),
     * counts open questions (threshold: 10 questions),
     * and detects unsafe inference (legal, financial, security, regulatory).
     * 
     * @param context The processing context to check
     * @return LoopDetectionResult with stop phrase if loop detected
     */
    LoopDetectionResult checkForLoops(ProcessingContext context);
}
