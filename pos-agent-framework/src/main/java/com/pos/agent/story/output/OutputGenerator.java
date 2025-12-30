package com.pos.agent.story.output;

import com.pos.agent.story.models.TransformedRequirements;

/**
 * Interface for generating final markdown output with mandatory section ordering.
 * Formats markdown structure and appends original story verbatim.
 * 
 * Requirements: 3.3, 3.4, 3.5, 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10, 4.11, 4.12, 9.1, 9.2, 9.3
 */
public interface OutputGenerator {
    
    /**
     * Generates final markdown output with mandatory section ordering.
     * Applies mandatory section ordering, formats markdown structure,
     * and appends original story verbatim.
     * 
     * @param transformed The transformed requirements
     * @param originalBody The original issue body to preserve
     * @return Final markdown string with all sections
     */
    String generateOutput(TransformedRequirements transformed, String originalBody);
}
