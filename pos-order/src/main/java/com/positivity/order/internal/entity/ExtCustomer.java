package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only customer-party replica (ADR-0044 §6, parity story I2 replica redesign): fed by
 * {@code customer.events.v1}, consulted by {@code ReplicaCustomerPortAdapter} for cart
 * customer-reference validation. pos-customer remains the system of record.
 */
@Entity
@Table(name = "ext_customer")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtCustomer {

    @Id
    @Column(name = "party_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID partyId;

    @Column(nullable = false)
    private String status;

    @Column
    private String displayName;

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
