package com.pos.agent.story.analysis;

import java.util.List;

/**
 * Validation result containing quality issues and suggestions.
 * 
 * This is a standalone class (not an inner class) to follow best practices
 * for public API components that are used across packages.
 */
public class ValidationResult {
    private final List<String> vagueTerms;
    private final List<String> missingAcceptanceCriteria;
    private final List<String> incompleteRequirements;
    private final List<String> unverifiableRequirements;
    private final List<String> terminologyInconsistencies;
    private final boolean isValid;
    
    public ValidationResult(
            List<String> vagueTerms,
            List<String> missingAcceptanceCriteria,
            List<String> incompleteRequirements,
            List<String> unverifiableRequirements,
            List<String> terminologyInconsistencies) {
        this.vagueTerms = vagueTerms;
        this.missingAcceptanceCriteria = missingAcceptanceCriteria;
        this.incompleteRequirements = incompleteRequirements;
        this.unverifiableRequirements = unverifiableRequirements;
        this.terminologyInconsistencies = terminologyInconsistencies;
        this.isValid = vagueTerms.isEmpty() && 
                      missingAcceptanceCriteria.isEmpty() && 
                      incompleteRequirements.isEmpty() &&
                      unverifiableRequirements.isEmpty() &&
                      terminologyInconsistencies.isEmpty();
    }
    
    public List<String> getVagueTerms() { return vagueTerms; }
    public List<String> getMissingAcceptanceCriteria() { return missingAcceptanceCriteria; }
    public List<String> getIncompleteRequirements() { return incompleteRequirements; }
    public List<String> getUnverifiableRequirements() { return unverifiableRequirements; }
    public List<String> getTerminologyInconsistencies() { return terminologyInconsistencies; }
    public boolean isValid() { return isValid; }
}
