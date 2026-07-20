package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.AllocationStrategy;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.*;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Request DTO for applying a payment to one or more invoices.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/114">Issue
 *      #114</a>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Schema(description = "Request payload for applying a payment to one or more invoices")
public class PaymentApplicationRequest {

    /**
     * Idempotency key for this application request.
     * Retries with same key must not create duplicate applications.
     */
    @Schema(
            description = "Idempotency key for this application request; retries with the same key must not duplicate",
            example = "pay-apply-20260618-001",
            requiredMode = REQUIRED)
    @NotBlank(message = "Application request ID is required for idempotency")
    @Size(max = 100, message = "Application request ID must not exceed 100 characters")
    private String applicationRequestId;

    /**
     * List of invoice applications (payment amount allocated to each invoice).
     */
    @Schema(description = "Invoice applications allocating the payment amount to each invoice", requiredMode = REQUIRED)
    @NotNull(message = "Applications list is required")
    @NotEmpty(message = "At least one invoice application is required")
    @Valid
    private List<InvoiceApplication> applications;

    /**
     * Optional allocation strategy. When absent, {@link AllocationStrategy#CALLER_ORDER}
     * is used and behavior is identical to requests that predate this field.
     */
    @Schema(
            description = "Optional allocation strategy. CALLER_ORDER (default when absent) applies amounts in "
                    + "the order supplied by the caller; OLDEST_FIRST allocates by ascending invoice date. "
                    + "Omitting this field is equivalent to CALLER_ORDER.",
            example = "OLDEST_FIRST",
            requiredMode = NOT_REQUIRED,
            nullable = true,
            defaultValue = "CALLER_ORDER")
    @Nullable
    private AllocationStrategy allocationStrategy;

    /**
     * Resolves the effective allocation strategy, applying the documented default.
     *
     * @return the caller-supplied strategy, or {@link AllocationStrategy#CALLER_ORDER} when absent
     */
    @NonNull
    public AllocationStrategy resolveAllocationStrategy() {
        return allocationStrategy != null ? allocationStrategy : AllocationStrategy.CALLER_ORDER;
    }

    /**
     * Individual invoice application within a payment application request.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    @Schema(description = "Single invoice application within a payment application request")
    public static class InvoiceApplication {

        @Schema(
                description = "Identifier of the invoice the payment is applied to",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = REQUIRED)
        @NotNull(message = "Invoice ID is required")
        private UUID invoiceId;

        @Schema(
                description = "Amount of the payment to apply to this invoice",
                example = "1250.00",
                requiredMode = REQUIRED)
        @NotNull(message = "Amount to apply is required")
        @DecimalMin(value = "0.01", message = "Amount to apply must be greater than 0")
        @Digits(integer = 15, fraction = 4, message = "Amount must have at most 15 integer digits and 4 decimal places")
        private BigDecimal amountToApply;
    }
}
