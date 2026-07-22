package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only invoice line replica fed by {@code invoice.events.v1} (ADR-0044 §6, #924). Rewritten as
 * a full replacement set for an invoice on every fact, mirroring the owner's snapshot semantics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_invoice_line")
public class ExtInvoiceLineReplica {

    @Id
    @Column(name = "invoice_item_id", nullable = false)
    private UUID invoiceItemId;

    @Column(name = "invoice_id", nullable = false)
    private UUID invoiceId;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    @Column(name = "amount")
    private BigDecimal amount;

    @Column(name = "workorder_item_id")
    private UUID workorderItemId;

    @Column(name = "item_type", length = 32)
    private String itemType;

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
