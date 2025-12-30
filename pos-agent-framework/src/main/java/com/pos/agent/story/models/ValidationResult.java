package com.pos.agent.story.models;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of validating a GitHub issue for Story Strengthening Agent processing.
 * Contains validation status and optional stop phrase if validation fails.
 * 
 * Requirements: 1.1, 1.2, 1.3, 1.4
 */
public class ValidationResult {
    private final boolean isValid;
    private final Optional<String> stopPhrase;
    private final Optional<String> reason;

    private ValidationResult(boolean isValid, String stopPhrase, String reason) {
        this.isValid = isValid;
        this.stopPhrase = Optional.ofNullable(stopPhrase);
        this.reason = Optional.ofNullable(reason);
    }

    public static ValidationResult valid() {
        return new ValidationResult(true, null, null);
    }

    public static ValidationResult invalid(String stopPhrase, String reason) {
        Objects.requireNonNull(stopPhrase, "Stop phrase cannot be null for invalid result");
        Objects.requireNonNull(reason, "Reason cannot be null for invalid result");
        return new ValidationResult(false, stopPhrase, reason);
    }

    public boolean isValid() {
        return isValid;
    }

    public Optional<String> getStopPhrase() {
        return stopPhrase;
    }

    public Optional<String> getReason() {
        return reason;
    }

    @Override
    public String toString() {
        if (isValid) {
            return "ValidationResult{valid}";
        }
        return String.format("ValidationResult{invalid, stopPhrase='%s', reason='%s'}", 
                           stopPhrase.orElse(""), reason.orElse(""));
    }
}
