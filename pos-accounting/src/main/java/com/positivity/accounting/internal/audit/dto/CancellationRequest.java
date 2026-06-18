package com.positivity.accounting.internal.audit.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.CancellationType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to record a cancellation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to record a cancellation audit event")
public class CancellationRequest {

    @Schema(
            description = "Identifier of the order being cancelled",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID orderId;

    @Schema(
            description = "Identifier of the invoice being cancelled",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID invoiceId;

    @Schema(description = "Type of cancellation being recorded", example = "ORDER_CANCELLED", requiredMode = REQUIRED)
    @NotNull(message = "Cancellation type is required")
    private CancellationType cancellationType;

    @Schema(description = "Snapshot of state before the cancellation (JSON)", requiredMode = REQUIRED)
    @NotNull(message = "Before snapshot is required")
    private String beforeSnapshot; // JSON string

    @Schema(description = "Snapshot of state after the cancellation (JSON)", requiredMode = REQUIRED)
    @NotNull(message = "After snapshot is required")
    private String afterSnapshot; // JSON string

    @Schema(description = "Partial payment details captured at cancellation (JSON)", requiredMode = NOT_REQUIRED)
    private String partialPaymentInfo; // JSON string, optional

    /**
     * Legacy client-provided actor identifier; service resolves authoritative
     * actor from authenticated security context.
     */
    @Schema(
            description = "Legacy client-provided actor identifier; service resolves authoritative actor from security context",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private String actorId;

    @Schema(description = "Role of the actor performing the cancellation", example = "STORE_MANAGER", requiredMode = REQUIRED)
    @NotNull(message = "Actor role is required")
    private String actorRole;

    @Schema(description = "Reason for the cancellation", example = "Customer requested cancellation", requiredMode = REQUIRED)
    @NotNull(message = "Reason is required")
    private String reason;
}
