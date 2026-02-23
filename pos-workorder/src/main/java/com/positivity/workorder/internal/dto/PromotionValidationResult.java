package com.positivity.workorder.internal.dto;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Result of promotion precondition validation.
 * Contains validation status and optional error details or existing resource
 * references.
 */
@Value
@Builder
public class PromotionValidationResult {

    /**
     * Whether all validation checks passed.
     */
    boolean valid;

    /**
     * Error message if validation failed.
     */
    @Nullable
    String errorMessage;

    /**
     * Structured error code if validation failed.
     */
    @Nullable
    String errorCode;

    /**
     * If promotion was already performed, reference to existing workorder.
     */
    @Nullable
    UUID existingWorkorderId;

    /**
     * Create a successful validation result.
     */
    @NonNull
    public static PromotionValidationResult success() {
        return PromotionValidationResult.builder()
                .valid(true)
                .build();
    }

    /**
     * Create a failed validation result with error code and message.
     */
    @NonNull
    public static PromotionValidationResult failure(
            @NonNull String errorCode,
            @NonNull String errorMessage) {
        return PromotionValidationResult.builder()
                .valid(false)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
    }

    /**
     * Create a failed validation result for duplicate promotion.
     */
    @NonNull
    public static PromotionValidationResult duplicate(
            @NonNull UUID existingWorkorderId,
            @NonNull String errorMessage) {
        return PromotionValidationResult.builder()
                .valid(false)
                .errorCode("ALREADY_PROMOTED")
                .errorMessage(errorMessage)
                .existingWorkorderId(existingWorkorderId)
                .build();
    }
}
