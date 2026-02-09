package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.accounting.internal.enums.PaymentMethod;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to execute an AP vendor payment.
 * 
 * <p>
 * Idempotency: paymentRef acts as unique idempotency key for duplicate
 * prevention.
 * 
 * <p>
 * Allocations: Optional explicit allocations; if omitted, automatic allocation
 * applied (oldest due date first).
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/128">Issue
 *      #128</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to execute an AP vendor payment")
public class ExecuteAPPaymentRequest {

    @NonNull
    @NotNull(message = "Vendor ID is required")
    @Schema(description = "Vendor UUID", example = "01936e5c-7890-7a3d-8b6e-2b3456789012", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("vendorId")
    private UUID vendorId;

    @NonNull
    @NotNull(message = "Gross amount is required")
    @DecimalMin(value = "0.01", message = "Gross amount must be positive")
    @Schema(description = "Gross payment amount", example = "1500.00", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("grossAmount")
    private BigDecimal grossAmount;

    @Nullable
    @Schema(description = "Fee amount (if known at capture time)", example = "15.00")
    @JsonProperty("feeAmount")
    private BigDecimal feeAmount;

    @Nullable
    @Schema(description = "Net amount deposited (if applicable)", example = "1485.00")
    @JsonProperty("netAmount")
    private BigDecimal netAmount;

    @NonNull
    @NotEmpty(message = "Currency is required")
    @Size(min = 3, max = 3, message = "Currency must be 3-character ISO code")
    @Schema(description = "ISO 4217 currency code", example = "USD", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("currency")
    private String currency;

    @NonNull
    @NotEmpty(message = "Payment reference is required")
    @Size(min = 1, max = 100, message = "Payment reference must be 1-100 characters")
    @Schema(description = "Unique payment reference (idempotency key)", example = "01936e5c-7890-7a3d-8b6e-2b3456789012", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("paymentRef")
    private String paymentRef;

    @NonNull
    @NotNull(message = "Payment method is required")
    @Schema(description = "Payment method (ACH, CHECK, WIRE, CREDIT_CARD, OTHER)", example = "ACH", requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonProperty("paymentMethod")
    private PaymentMethod paymentMethod;

    @Nullable
    @Size(max = 1000, message = "Memo must not exceed 1000 characters")
    @Schema(description = "Optional payment memo/notes", example = "Monthly supplier payment")
    @JsonProperty("memo")
    private String memo;

    @Nullable
    @Schema(description = "Optional explicit allocations to vendor bills. If omitted, automatic allocation applied.")
    @JsonProperty("allocations")
    private List<AllocationLineRequest> allocations;

    /**
     * Allocation line for a specific vendor bill.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Allocation line for a specific vendor bill")
    public static class AllocationLineRequest {

        @NonNull
        @NotNull(message = "Vendor bill ID is required")
        @Schema(description = "Vendor bill UUID", example = "01936e5d-1234-7a3d-8b6e-3c4567890123", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("vendorBillId")
        private UUID vendorBillId;

        @NonNull
        @NotNull(message = "Applied amount is required")
        @DecimalMin(value = "0.00", inclusive = true, message = "Applied amount must be non-negative")
        @Schema(description = "Amount to apply to this bill", example = "750.00", requiredMode = Schema.RequiredMode.REQUIRED)
        @JsonProperty("appliedAmount")
        private BigDecimal appliedAmount;
    }
}
