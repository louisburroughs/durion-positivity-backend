package com.positivity.people.internal.exception;

/**
 * A vendor credential code with no row in {@code skill_code_xref} (CAP-328, spec D8). Fails the
 * ingest loudly — 422 {@code SEMANTIC_VALIDATION_ERROR} — rather than becoming a skill nobody
 * holds: the registry is the vocabulary, and a code outside it is a data defect to fix at the
 * source or in the seed, never something to store.
 */
public class UnknownSkillCodeException extends SemanticValidationException {
    public UnknownSkillCodeException(String sourceCode, String sourceSkillCode) {
        super("Unknown " + sourceCode + " credential code '" + sourceSkillCode
                + "': not in skill_code_xref. Add the cross-reference to the skill registry seed or correct the source.");
    }
}
