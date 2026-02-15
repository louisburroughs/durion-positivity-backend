package com.positivity.workorder.internal.exception;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import lombok.Getter;

import java.util.UUID;

/**
 * Exception thrown when estimate promotion validation fails.
 * Contains structured error codes and optional existing resource references.
 */
@Getter
public class PromotionValidationException extends RuntimeException {

    private final PromotionErrorCode errorCode;
    private final UUID existingWorkorderId;

    public PromotionValidationException(
            @NonNull PromotionErrorCode errorCode,
            @NonNull String message) {
        this(errorCode, message, null);
    }

    public PromotionValidationException(
            @NonNull PromotionErrorCode errorCode,
            @NonNull String message,
            @Nullable UUID existingWorkorderId) {
        super(message);
        this.errorCode = errorCode;
        this.existingWorkorderId = existingWorkorderId;
    }

    /**
     * Error codes for promotion validation failures.
     * Aligned with BACKEND_CONTRACT_GUIDE.md error codes.
     */
    public enum PromotionErrorCode {
        /**
         * Estimate already promoted to workorder. Response contains existingResourceId.
         */
        ALREADY_PROMOTED,

        /**
         * Approval has expired.
         */
        APPROVAL_EXPIRED,

        /**
         * Approval status is invalid (not APPROVED).
         */
        APPROVAL_INVALID,

        /**
         * No approval record found for estimate.
         */
        APPROVAL_NOT_FOUND,

        /**
         * Estimate not found.
         */
        ESTIMATE_NOT_FOUND,

        /**
         * Estimate has no approved items.
         */
        NO_APPROVED_ITEMS,

        /**
         * Invalid state for promotion.
         */
        INVALID_STATE
    }
}
