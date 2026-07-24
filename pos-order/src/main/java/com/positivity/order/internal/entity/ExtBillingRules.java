package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only commercial billing-rules replica (parity story C4, spec R4.5): fed by
 * {@code customer.billing-rules.updated} on {@code customer.events.v1} (pos-customer's
 * {@code BillingRulesEmbeddable} is the system of record). Checkout's ON_ACCOUNT gate requires a
 * row with payment terms and no credit hold.
 */
@Entity
@Table(name = "ext_billing_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtBillingRules {

    @Id
    @Column(name = "party_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID partyId;

    @Column(length = 100)
    private String paymentTerms;

    @Column(precision = 19, scale = 4)
    private BigDecimal creditLimit;

    @Column
    private Boolean creditHold;

    @Column
    private Boolean poRequired;

    /** Envelope aggregateVersion of the last applied event — stale-event guard. */
    @Column(nullable = false)
    private long aggregateVersion;

    @Column(nullable = false)
    private Instant syncedAt;

    /** ArchUnit UUIDv7 rule hook: partyId is a UUIDv7 issued by pos-customer. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
