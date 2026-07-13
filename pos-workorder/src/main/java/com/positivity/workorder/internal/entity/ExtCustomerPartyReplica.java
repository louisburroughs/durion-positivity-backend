package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
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
 * Read-only customer-party replica fed by {@code customer.events.v1}
 * ({@code customer.party.updated}/{@code deleted}, ADR-0044 §6, #891). pos-customer owns the
 * facts; this module only reads the owner-computed {@code requirementsMet} verdict that the
 * retired synchronous {@code CustomerValidationClient} used to fetch per call.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
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

    @Column(name = "requirements_met", nullable = false)
    private boolean requirementsMet;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
