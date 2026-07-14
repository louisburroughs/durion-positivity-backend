package com.positivity.invoice.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Read-only customer-party replica fed by {@code customer.events.v1}
 * ({@code customer.party.updated}/{@code deleted}, ADR-0044 §6, #891). pos-customer owns the
 * facts; this module only searches display names and resolves them for invoice search rows —
 * the surface previously served synchronously by {@code CustomerReferenceClient}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_customer_party")
public class ExtCustomerPartyReplica {

    @Id
    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @Column(name = "party_type", nullable = false, length = 32)
    private String partyType;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013, generator-owned upstream): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
