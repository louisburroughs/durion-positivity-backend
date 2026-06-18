package com.positivity.accounting.internal.audit.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.RefundPaymentStatus;
import com.positivity.accounting.internal.enums.RefundType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to record a refund.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request to record a refund audit event")
public class RefundRequest {

    @Schema(
            description = "Identifier of the invoice being refunded",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull(message = "Invoice ID is required")
    private UUID invoiceId;

    @Schema(
            description = "Identifier of the payment being refunded",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull(message = "Payment ID is required")
    private UUID paymentId;

    @Schema(description = "Type of refund being recorded", example = "REVERSAL", requiredMode = REQUIRED)
    @NotNull(message = "Refund type is required")
    private RefundType refundType;

    @Schema(description = "Amount being refunded", example = "1250.00", requiredMode = REQUIRED)
    @NotNull(message = "Refund amount is required")
    private BigDecimal refundAmount;

    @Schema(description = "Status of the original payment being refunded", example = "SETTLED", requiredMode = REQUIRED)
    @NotNull(message = "Original payment status is required")
    private RefundPaymentStatus originalPaymentStatus;

    /**
     * Legacy client-provided actor identifier; service resolves authoritative
     * actor from authenticated security context.
     */
    @Schema(
            description = "Legacy client-provided actor identifier; service resolves authoritative actor from security context",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private String actorId;

    @Schema(description = "Role of the actor performing the refund", example = "STORE_MANAGER", requiredMode = REQUIRED)
    @NotNull(message = "Actor role is required")
    private String actorRole;

    @Schema(description = "Reason for the refund", example = "Defective product returned", requiredMode = REQUIRED)
    @NotNull(message = "Reason is required")
    private String reason;
}
