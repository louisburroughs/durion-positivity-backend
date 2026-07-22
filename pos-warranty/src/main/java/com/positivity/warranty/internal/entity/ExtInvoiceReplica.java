package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shared.id.UUIDv7Id;
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
 * Read-only invoice header replica fed by {@code invoice.events.v1} (ADR-0044 §6, #924).
 *
 * <p>pos-invoice owns these facts; only the {@code InvoiceEventsListener} consumer writes this
 * table. Serves candidate-line origin search (invoice lines by party) previously answered by the
 * retired synchronous {@code InvoiceClient.searchInvoiceLines}. Line items live in
 * {@link ExtInvoiceLineReplica}. Settlement writes/reconciliation still use the sync InvoiceClient.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_invoice")
public class ExtInvoiceReplica {

    @Id
    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "invoice_number", length = 64)
    private String invoiceNumber;

    @Column(name = "party_id", length = 64)
    private String partyId;

    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "invoice_created_at")
    private Instant invoiceCreatedAt;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hooks for the ArchUnit UUIDv7 rules (ADR-0013): the PK is a UUIDv7 copied
     * verbatim from the owning aggregate's event, so the replica generates no identifier of its own.
     * References both {@link UUIDv7Id} (module ArchitectureTest) and {@link UUIDv7Generator}
     * (cross-module EntityStandards) so both identifier-standard rules recognise the replica.
     */
    @Transient
    public Class<?>[] uuidv7Dependency() {
        return new Class<?>[] {UUIDv7Id.class, UUIDv7Generator.class};
    }
}
