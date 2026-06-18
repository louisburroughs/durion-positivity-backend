package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import com.positivity.customer.internal.enums.InvoiceDeliveryMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to upsert billing rules for a commercial party")
public class UpsertBillingRulesRequest {

    @Schema(description = "Payment terms string", example = "Net 30", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 100)
    private String paymentTerms;

    @Schema(
            description = "Maximum credit limit; null means no configured limit",
            example = "10000.00",
            requiredMode = NOT_REQUIRED)
    @Nullable
    @DecimalMin("0.00")
    private BigDecimal creditLimit;

    @Schema(description = "ISO-4217 currency code", example = "USD", requiredMode = NOT_REQUIRED)
    @Nullable
    @Pattern(regexp = "^[A-Z]{3}$", message = "Currency must be a 3-letter ISO-4217 code")
    private String currency;

    @Schema(description = "Whether the account is tax-exempt", example = "false", requiredMode = NOT_REQUIRED)
    private boolean taxExempt;

    @Schema(
            description = "Whether a PO number is required before order can be finalized",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private boolean poRequired;

    @Schema(description = "Whether the account is on a credit hold", example = "false", requiredMode = NOT_REQUIRED)
    private boolean creditHold;

    @Schema(
            description = "Whether auto-pay is enabled for this account",
            example = "false",
            requiredMode = NOT_REQUIRED)
    private boolean autoPayEnabled;

    @Schema(description = "Preferred invoice delivery method", requiredMode = NOT_REQUIRED)
    @Nullable
    private InvoiceDeliveryMethod invoiceDeliveryMethod;

    @Schema(
            description = "Billing address ID",
            example = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private UUID billingAddressId;

    @Schema(
            description = "Optional reference to a discount policy",
            example = "DISC-GOLD-001",
            requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 200)
    private String discountPolicyRef;
}
