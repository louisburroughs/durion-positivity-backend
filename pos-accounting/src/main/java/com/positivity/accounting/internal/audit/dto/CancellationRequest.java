package com.positivity.accounting.internal.audit.dto;

import com.positivity.accounting.internal.enums.CancellationType;
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
public class CancellationRequest {

    private UUID orderId;

    private UUID invoiceId;

    @NotNull(message = "Cancellation type is required")
    private CancellationType cancellationType;

    @NotNull(message = "Before snapshot is required")
    private String beforeSnapshot; // JSON string

    @NotNull(message = "After snapshot is required")
    private String afterSnapshot; // JSON string

    private String partialPaymentInfo; // JSON string, optional

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
