package com.positivity.accounting.internal.audit.dto;

import com.positivity.accounting.internal.enums.RefundPaymentStatus;
import com.positivity.accounting.internal.enums.RefundType;
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
public class RefundRequest {

    @NotNull(message = "Invoice ID is required")
    private UUID invoiceId;

    @NotNull(message = "Payment ID is required")
    private UUID paymentId;

    @NotNull(message = "Refund type is required")
    private RefundType refundType;

    @NotNull(message = "Refund amount is required")
    private BigDecimal refundAmount;

    @NotNull(message = "Original payment status is required")
    private RefundPaymentStatus originalPaymentStatus;

    /**
     * Legacy client-provided actor identifier; service resolves authoritative
     * actor from authenticated security context.
     */
    private String actorId;

    @NotNull(message = "Actor role is required")
    private String actorRole;

    @NotNull(message = "Reason is required")
    private String reason;
}
