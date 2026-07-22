package com.positivity.warranty.internal.entity;

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

    /** Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): PK is a UUIDv7 verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
