package com.positivity.positivity.agent.collaboration;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Result of consistency validation between agent responses
 */
public record ConsistencyValidationResult(
    boolean isConsistent,
    double consistencyScore,
    List<String> conflicts,
    List<String> agreements,
    Map<String, Object> validationDetails,
    Duration validationTime,
    Instant timestamp
) {
    
    public static ConsistencyValidationResult consistent(double score, List<String> agreements, Duration validationTime) {
        return new ConsistencyValidationResult(
            true,
            score,
            List.of(),
            agreements,
            Map.of("validation_passed", true),
            validationTime,
            Instant.now()
        );
    }
    
    public static ConsistencyValidationResult inconsistent(double score, List<String> conflicts, Duration validationTime) {
        return new ConsistencyValidationResult(
            false,
            score,
            conflicts,
            List.of(),
            Map.of("validation_passed", false, "conflict_count", conflicts.size()),
            validationTime,
            Instant.now()
        );
    }
    
    public static ConsistencyValidationResult failed(String reason) {
        return new ConsistencyValidationResult(
            false,
            0.0,
            List.of("Validation failed: " + reason),
            List.of(),
            Map.of("validation_error", reason),
            Duration.ZERO,
            Instant.now()
        );
    }
    
    public boolean meetsConsistencyThreshold(double threshold) {
        return consistencyScore >= threshold;
    }
}