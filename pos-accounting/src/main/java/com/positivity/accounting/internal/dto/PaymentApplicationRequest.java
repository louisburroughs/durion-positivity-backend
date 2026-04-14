package com.positivity.accounting.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.*;

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
public class PaymentApplicationRequest {

    /**
     * Idempotency key for this application request.
     * Retries with same key must not create duplicate applications.
     */
    @NotBlank(message = "Application request ID is required for idempotency")
    @Size(max = 100, message = "Application request ID must not exceed 100 characters")
    private String applicationRequestId;

    /**
     * List of invoice applications (payment amount allocated to each invoice).
     */
    @NotNull(message = "Applications list is required")
    @NotEmpty(message = "At least one invoice application is required")
    @Valid
    private List<InvoiceApplication> applications;

    /**
     * Individual invoice application within a payment application request.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    public static class InvoiceApplication {

        @NotNull(message = "Invoice ID is required")
        private UUID invoiceId;

        @NotNull(message = "Amount to apply is required")
        @DecimalMin(value = "0.01", message = "Amount to apply must be greater than 0")
        @Digits(integer = 15, fraction = 4, message = "Amount must have at most 15 integer digits and 4 decimal places")
        private BigDecimal amountToApply;
    }
}
