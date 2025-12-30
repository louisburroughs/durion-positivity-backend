package com.pos.agent.story.loop;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of loop detection check.
 * Contains loop detection status and optional stop phrase.
 * 
 * Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7
 */
public class LoopDetectionResult {
    private final boolean loopDetected;
    private final Optional<String> stopPhrase;
    private final Optional<String> details;

    private LoopDetectionResult(boolean loopDetected, String stopPhrase, String details) {
        this.loopDetected = loopDetected;
        this.stopPhrase = Optional.ofNullable(stopPhrase);
        this.details = Optional.ofNullable(details);
    }

    public static LoopDetectionResult noLoop() {
        return new LoopDetectionResult(false, null, null);
    }

    public static LoopDetectionResult loopDetected(String stopPhrase, String details) {
        Objects.requireNonNull(stopPhrase, "Stop phrase cannot be null for loop detection");
        Objects.requireNonNull(details, "Details cannot be null for loop detection");
        return new LoopDetectionResult(true, stopPhrase, details);
    }

    public boolean isLoopDetected() {
        return loopDetected;
    }

    public Optional<String> getStopPhrase() {
        return stopPhrase;
    }

    public Optional<String> getDetails() {
        return details;
    }

    @Override
    public String toString() {
        if (!loopDetected) {
            return "LoopDetectionResult{noLoop}";
        }
        return String.format("LoopDetectionResult{loopDetected, stopPhrase='%s', details='%s'}", 
                           stopPhrase.orElse(""), details.orElse(""));
    }
}
