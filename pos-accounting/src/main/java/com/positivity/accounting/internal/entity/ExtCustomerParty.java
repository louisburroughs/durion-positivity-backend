package com.positivity.accounting.internal.entity;

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
 * Read-only replica of pos-customer party identity (ADR-0044 §6, issues #889 / #891), fed
 * exclusively by the {@code customer.events.v1} consumer. Accounting business logic only reads
 * it, and only to render display values — never to derive a posting amount or a ledger fact.
 *
 * <p>Added for issue #1779: customer-facing accounting responses (credit memos to start with)
 * must show a customer's name or customer number rather than the raw party UUID, and pos-customer
 * owns those facts. The same event this replica consumes already backs the equivalent
 * {@code ext_customer_party} replicas in pos-invoice, pos-workorder and pos-shop-manager; this is
 * the accounting-side copy, extended with {@code customer_number} (the owner's stable
 * human-facing reference, which the other consumers had no use for).
 *
 * <p>{@code displayName} and {@code customerNumber} are both nullable at the source, so callers
 * must treat an absent display value as absent — never substitute the UUID.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "ext_customer_party")
public class ExtCustomerParty {

    @Id
    @Column(name = "party_id", columnDefinition = "UUID")
    private UUID partyId;

    @Column(name = "party_type", nullable = false, length = 32)
    private String partyType;

    /** CRM-resolved display name; null when the owner knows no identity for the party yet. */
    @Column(name = "display_name", length = 255)
    private String displayName;

    /** Owner's stable human-facing customer number; null on parties the owner never numbered. */
    @Column(name = "customer_number", length = 64)
    private String customerNumber;

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
