package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import lombok.Data;

/**
 * Billing rules snapshot returned by pos-customer.
 */
@Data
@Schema(description = "Billing rules snapshot returned by pos-customer for a customer account")
public class BillingRuleRefResponse {

    @Schema(description = "Whether a purchase order is required for billing", example = "true", requiredMode = REQUIRED)
    private boolean poRequired;

    @Schema(description = "Whether the customer is tax exempt", example = "false", requiredMode = REQUIRED)
    private boolean taxExempt;

    @Schema(description = "Agreed payment terms", example = "NET30", requiredMode = NOT_REQUIRED)
    private String paymentTerms;

    @Schema(description = "Credit limit extended to the customer", example = "50000.00", requiredMode = NOT_REQUIRED)
    private BigDecimal creditLimit;

    @Schema(
            description = "Whether the customer is currently on credit hold",
            example = "false",
            requiredMode = REQUIRED)
    private boolean creditHold;

    @Schema(description = "Method used to deliver invoices", example = "EMAIL", requiredMode = NOT_REQUIRED)
    private String invoiceDeliveryMethod;

    @Schema(
            description = "Identifier of the billing address",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID billingAddressId;

    @Schema(description = "Whether automatic payment is enabled", example = "false", requiredMode = REQUIRED)
    private boolean autoPayEnabled;

    @Schema(
            description = "Reference to the applicable discount policy",
            example = "STD-2026",
            requiredMode = NOT_REQUIRED)
    private String discountPolicyRef;

    @Schema(description = "ISO 4217 currency code", example = "USD", requiredMode = NOT_REQUIRED)
    private String currency;

    @Schema(description = "Additional extension attributes keyed by name", requiredMode = NOT_REQUIRED)
    private Map<String, Object> extensions;
}
