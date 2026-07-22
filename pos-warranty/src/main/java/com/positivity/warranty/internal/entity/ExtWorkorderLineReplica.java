package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
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
 * Read-only workorder line replica (part + service lines) fed by {@code workorder.events.v1}
 * (ADR-0044 §6, #924). {@code lineKind} is {@code PART} or {@code SERVICE}. Rewritten as a full
 * replacement set for a workorder on every fact, mirroring the owner's snapshot semantics.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_workorder_line")
public class ExtWorkorderLineReplica {

    @Id
    @Column(name = "workorder_line_id", nullable = false)
    private UUID workorderLineId;

    @Column(name = "workorder_id", nullable = false)
    private UUID workorderId;

    @Column(name = "line_kind", length = 16, nullable = false)
    private String lineKind;

    @Column(name = "product_entity_id")
    private UUID productEntityId;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    @Column(name = "line_total")
    private BigDecimal lineTotal;

    @Column(name = "photo_evidence_url", length = 1024)
    private String photoEvidenceUrl;

    /** Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): PK is a UUIDv7 verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
