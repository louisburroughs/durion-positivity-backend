package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Allocate an unapplied {@code CASH} AP payment to the vendor's approved bills (CAP:550 S17, #2513; AW41). The
 * allocation posts nothing: AP was debited at session close and credited at bill approval; this matches the
 * subledger.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Allocate an unapplied cash-on-delivery AP payment to the vendor's approved bills")
public class AllocateAPPaymentRequest {

    @NotNull(message = "requestId is required")
    @Schema(
            description = "Caller-minted idempotency key: a replay with the same lines answers the same payment;"
                    + " another body under the same id is 409 IDEMPOTENCY_CONFLICT",
            example = "01936e5c-1234-7a3d-8b6e-123456789012")
    private UUID requestId;

    @NotEmpty(message = "allocations must name at least one bill")
    @Valid
    @Schema(description = "The bills to apply the payment to; the total may not exceed the unapplied amount")
    private List<ExecuteAPPaymentRequest.AllocationLineRequest> allocations;
}
