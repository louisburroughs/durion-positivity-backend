package com.pos.agent.story.transformation;

import com.pos.agent.story.models.AnalysisResult;
import com.pos.agent.story.models.TransformedRequirements;

/**
 * Interface for transforming analyzed content into EARS/Gherkin format.
 * Applies EARS patterns and converts scenarios to Gherkin syntax.
 * 
 * Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 6.1, 6.2, 6.3, 6.4, 6.5, 7.1, 7.2, 7.3, 7.4, 7.6
 */
public interface RequirementsTransformer {
    
    /**
     * Transforms analyzed content into EARS/Gherkin format.
     * Applies EARS patterns (ubiquitous, state-driven, event-driven, unwanted),
     * converts scenarios to Gherkin (Given/When/Then),
     * ensures ISO/IEC/IEEE 29148 compliance,
     * and structures open questions.
     * 
     * @param analysis The analysis result to transform
     * @return TransformedRequirements with formatted content
     */
    TransformedRequirements transformRequirements(AnalysisResult analysis);
}
