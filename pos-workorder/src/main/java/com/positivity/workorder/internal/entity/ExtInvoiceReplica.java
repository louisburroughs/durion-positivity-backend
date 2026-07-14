package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
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
 * Read-only invoice replica fed by {@code invoice.events.v1} (ADR-0044 §6, #900).
 *
 * <p>pos-invoice owns these facts; nothing in this module may write the table except the event
 * consumer. Serves the generate-invoice read path previously answered by the retired
 * {@code InvoiceClient.getInvoice}, and resolves the async generation pending state by linking
 * {@code workorder.invoiceId} when the fact for a workorder arrives.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_invoice")
public class ExtInvoiceReplica {

    @Id
    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "workorder_id")
    private UUID workorderId;

    @Column(name = "invoice_number", length = 64)
    private String invoiceNumber;

    @Column(name = "status", length = 64)
    private String status;

    @Column(name = "subtotal")
    private BigDecimal subtotal;

    @Column(name = "tax")
    private BigDecimal tax;

    @Column(name = "total")
    private BigDecimal total;

    @Column(name = "invoice_created_at")
    private Instant invoiceCreatedAt;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
