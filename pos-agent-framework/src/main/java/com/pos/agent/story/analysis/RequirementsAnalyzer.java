package com.pos.agent.story.analysis;

import com.pos.agent.story.models.AnalysisResult;
import com.pos.agent.story.models.ParsedIssue;

/**
 * Interface for analyzing issue content and identifying requirements elements.
 * Extracts actors, intent, preconditions, requirements, and ambiguities.
 * 
 * Requirements: 3.1, 7.1, 7.3, 8.1, 8.2, 8.3, 8.4, 8.5
 */
public interface RequirementsAnalyzer {
    
    /**
     * Analyzes parsed issue content to identify requirements elements.
     * Identifies actors, stakeholders, business intent, preconditions,
     * functional requirements, error flows, business rules, data requirements,
     * and flags ambiguities.
     * 
     * @param parsedIssue The parsed issue to analyze
     * @return AnalysisResult with structured findings
     */
    AnalysisResult analyzeRequirements(ParsedIssue parsedIssue);
}
