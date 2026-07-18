package com.positivity.accounting.internal.exception;

import java.util.List;

/**
 * Thrown when a posting rules definition fails split-group validation at
 * publish time (story E1, issue #945).
 *
 * <p>Carries every violation found in the rules definition so the caller can
 * fix all problems in one pass. Maps to HTTP 422 with error code
 * {@code UNBALANCED_RULES} (see the accounting domain
 * {@code ERROR_CODES.md}); each violation is surfaced as an
 * {@code ApiError.FieldError} whose {@code field} is a JSON-pointer-style
 * locator into the rules definition (e.g.
 * {@code conditions[0].splitGroup[tax]} or
 * {@code conditions[1].lines[2].factorPercent}).
 */
public class UnbalancedRulesException extends RuntimeException {

    /**
     * One split-group validation violation.
     *
     * @param field   locator into the rules definition identifying the
     *                offending group or line
     * @param message human-readable description of the violation
     */
    public record RuleViolation(String field, String message) {}

    private final transient List<RuleViolation> violations;

    public UnbalancedRulesException(List<RuleViolation> violations) {
        super(buildMessage(violations));
        this.violations = List.copyOf(violations);
    }

    public List<RuleViolation> getViolations() {
        return violations;
    }

    private static String buildMessage(List<RuleViolation> violations) {
        StringBuilder sb = new StringBuilder("Cannot publish: posting rules definition violates split-group ")
                .append("invariants (")
                .append(violations.size())
                .append(violations.size() == 1 ? " violation): " : " violations): ");
        for (int i = 0; i < violations.size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            sb.append(violations.get(i).field())
                    .append(": ")
                    .append(violations.get(i).message());
        }
        return sb.toString();
    }
}
