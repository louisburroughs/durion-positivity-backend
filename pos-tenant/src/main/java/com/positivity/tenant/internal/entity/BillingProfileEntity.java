package com.positivity.tenant.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * How an account is invoiced (ADR-0062 §7): billing address, payment terms, invoicing email and
 * the payment processor's customer token. Never a card number. One per account.
 */
@Entity
@Table(name = "billing_profile")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BillingProfileEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "address_line1", nullable = false, length = 200)
    private String addressLine1;

    @Column(name = "address_line2", length = 200)
    private String addressLine2;

    @Column(nullable = false, length = 100)
    private String city;

    @Column(length = 100)
    private String region;

    @Column(name = "postal_code", length = 20)
    private String postalCode;

    /** ISO 3166-1 alpha-2. */
    @Column(nullable = false, length = 2)
    private String country;

    /** Free text such as {@code NET30}; a plan or subscription attaches later. */
    @Column(name = "payment_terms", nullable = false, length = 32)
    private String paymentTerms;

    @Column(name = "invoicing_email", nullable = false, length = 320)
    private String invoicingEmail;

    /** Opaque token issued by the payment processor; never a card number. */
    @Column(name = "payment_processor_customer_token", length = 128)
    private String paymentProcessorCustomerToken;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
