package com.positivity.customer.internal.entity;

import com.positivity.customer.internal.enums.InvoiceDeliveryMethod;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.Data;
import org.jspecify.annotations.Nullable;

@Embeddable
@Data
public class BillingRulesEmbeddable {

  @Column(name = "br_po_required")
  @Nullable
  private Boolean poRequired;

  @Column(name = "br_tax_exempt")
  @Nullable
  private Boolean taxExempt;

  @Column(name = "br_credit_hold")
  @Nullable
  private Boolean creditHold;

  @Column(name = "br_auto_pay_enabled")
  @Nullable
  private Boolean autoPayEnabled;

  @Column(name = "br_payment_terms", length = 100)
  @Nullable
  private String paymentTerms;

  @Column(name = "br_credit_limit", precision = 19, scale = 4)
  @Nullable
  private BigDecimal creditLimit;

  @Column(name = "br_currency", length = 3)
  @Nullable
  private String currency;

  @Column(name = "br_invoice_delivery_method", length = 50)
  @Enumerated(EnumType.STRING)
  @Nullable
  private InvoiceDeliveryMethod invoiceDeliveryMethod;

  @Column(name = "br_billing_address_id")
  @Nullable
  private UUID billingAddressId;

  @Column(name = "br_discount_policy_ref", length = 200)
  @Nullable
  private String discountPolicyRef;
}
