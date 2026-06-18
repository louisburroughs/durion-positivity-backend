package com.positivity.customer.internal.dto.snapshot;

import com.positivity.customer.internal.enums.InvoiceDeliveryMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read-only billing rule reference surfaced in CRM snapshots.
 * CAP:252 — Story #3, Story #4.
 *
 * <p>
 * This service provides billing rules as a configuration snapshot only.
 * Enforcement is the responsibility of downstream services (Checkout,
 * Invoicing, Pricing).
 */
@Schema(description = "Read-only billing rule reference surfaced in a CRM snapshot")
public class BillingRuleRef {

    /** Whether a PO number is required before order can be finalized. */
    @Schema(
            description = "Whether a PO number is required before an order can be finalized",
            example = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean poRequired;

    /** Whether the customer is tax-exempt. */
    @Schema(description = "Whether the customer is tax-exempt", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean taxExempt;

    /** Payment terms string, e.g. "Net 30", "Due on Receipt". */
    @Schema(description = "Payment terms string", example = "Net 30", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private String paymentTerms;

    /** Maximum credit limit available; null means no configured limit. */
    @Schema(
            description = "Maximum credit limit available; null means no configured limit",
            example = "10000.00",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private BigDecimal creditLimit;

    /** Whether the account is on a credit hold. */
    @Schema(
            description = "Whether the account is on a credit hold",
            example = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean creditHold;

    /** Preferred invoice delivery method. */
    @Schema(description = "Preferred invoice delivery method", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private InvoiceDeliveryMethod invoiceDeliveryMethod;

    /** Billing address ID; null if not configured. */
    @Schema(
            description = "Billing address ID; null if not configured",
            example = "f47ac10b-58cc-4372-a567-0e02b2c3d479",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private UUID billingAddressId;

    /** Whether auto-pay is enabled for this account. */
    @Schema(
            description = "Whether auto-pay is enabled for this account",
            example = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean autoPayEnabled;

    /** Optional reference to a discount policy. */
    @Schema(
            description = "Optional reference to a discount policy",
            example = "DISC-GOLD-001",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private String discountPolicyRef;

    /** ISO-4217 currency code; null if not configured. */
    @Schema(
            description = "ISO-4217 currency code; null if not configured",
            example = "USD",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private String currency;

    /** Extension map for future fields. Consumers MUST ignore unknown keys. */
    @Schema(
            description = "Extension map for future fields; consumers must ignore unknown keys",
            example = "{\"futureFlag\": true}",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private Map<String, Object> extensions;

    public BillingRuleRef() {}

    public boolean isPoRequired() {
        return poRequired;
    }

    public void setPoRequired(boolean poRequired) {
        this.poRequired = poRequired;
    }

    public boolean isTaxExempt() {
        return taxExempt;
    }

    public void setTaxExempt(boolean taxExempt) {
        this.taxExempt = taxExempt;
    }

    @Nullable
    public String getPaymentTerms() {
        return paymentTerms;
    }

    public void setPaymentTerms(@Nullable String paymentTerms) {
        this.paymentTerms = paymentTerms;
    }

    @Nullable
    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(@Nullable BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    public boolean isCreditHold() {
        return creditHold;
    }

    public void setCreditHold(boolean creditHold) {
        this.creditHold = creditHold;
    }

    @Nullable
    public InvoiceDeliveryMethod getInvoiceDeliveryMethod() {
        return invoiceDeliveryMethod;
    }

    public void setInvoiceDeliveryMethod(@Nullable InvoiceDeliveryMethod method) {
        this.invoiceDeliveryMethod = method;
    }

    @Nullable
    public UUID getBillingAddressId() {
        return billingAddressId;
    }

    public void setBillingAddressId(@Nullable UUID billingAddressId) {
        this.billingAddressId = billingAddressId;
    }

    public boolean isAutoPayEnabled() {
        return autoPayEnabled;
    }

    public void setAutoPayEnabled(boolean autoPayEnabled) {
        this.autoPayEnabled = autoPayEnabled;
    }

    @Nullable
    public String getDiscountPolicyRef() {
        return discountPolicyRef;
    }

    public void setDiscountPolicyRef(@Nullable String discountPolicyRef) {
        this.discountPolicyRef = discountPolicyRef;
    }

    @Nullable
    public String getCurrency() {
        return currency;
    }

    public void setCurrency(@Nullable String currency) {
        this.currency = currency;
    }

    @Nullable
    public Map<String, Object> getExtensions() {
        return extensions;
    }

    public void setExtensions(@Nullable Map<String, Object> extensions) {
        this.extensions = extensions;
    }

    /**
     * Returns a BillingRuleRef with all default values applied per Issue #4
     * business rules.
     */
    @NonNull
    public static BillingRuleRef defaults() {
        BillingRuleRef ref = new BillingRuleRef();
        ref.poRequired = false;
        ref.taxExempt = false;
        ref.paymentTerms = "Due on Receipt";
        ref.creditLimit = null;
        ref.creditHold = false;
        ref.invoiceDeliveryMethod = InvoiceDeliveryMethod.EMAIL;
        ref.autoPayEnabled = false;
        return ref;
    }
}
