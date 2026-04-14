package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Result of promotion precondition validation.
 * Contains validation status and optional error details or existing resource
 * references.
 */
@Value
@Builder
@Schema(description = "Result of promotion precondition validation")
public class PromotionValidationResult {

    /**
     * Whether all validation checks passed.
     */
    @Schema(description = "Indicates whether promotion validation succeeded", example = "true")
    boolean valid;

    /**
     * Error message if validation failed.
     */
    @Nullable
    @Schema(
            description = "Validation failure message when valid=false",
            example = "Estimate has unresolved required approvals")
    String errorMessage;

    /**
     * Structured error code if validation failed.
     */
    @Nullable
    @Schema(description = "Machine-readable validation error code", example = "APPROVAL_REQUIRED")
    String errorCode;

    /**
     * If promotion was already performed, reference to existing workorder.
     */
    @Nullable
    @Schema(
            description = "Existing workorder identifier when promotion already occurred",
            example = "550e8400-e29b-41d4-a716-446655440000")
    UUID existingWorkorderId;

    /**
     * Create a successful validation result.
     */
    @NonNull
    public static PromotionValidationResult success() {
        return PromotionValidationResult.builder().valid(true).build();
    }

    /**
     * Create a failed validation result with error code and message.
     */
    @NonNull
    public static PromotionValidationResult failure(@NonNull String errorCode, @NonNull String errorMessage) {
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
    public static PromotionValidationResult duplicate(@NonNull UUID existingWorkorderId, @NonNull String errorMessage) {
        return PromotionValidationResult.builder()
                .valid(false)
                .errorCode("ALREADY_PROMOTED")
                .errorMessage(errorMessage)
                .existingWorkorderId(existingWorkorderId)
                .build();
    }
}
