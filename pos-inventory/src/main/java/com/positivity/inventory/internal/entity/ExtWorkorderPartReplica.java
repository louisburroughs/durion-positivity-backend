package com.positivity.inventory.internal.entity;

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
 * Read-only workorder part-line replica carried on {@code workorder.workorder.updated} facts
 * (ADR-0044 §6, #897). Every fact carries the workorder's full part-line set — the consumer
 * replaces the workorder's rows wholesale.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_workorder_part")
public class ExtWorkorderPartReplica {

    @Id
    @Column(name = "workorder_line_id", nullable = false)
    private UUID workorderLineId;

    @Column(name = "workorder_id", nullable = false)
    private UUID workorderId;

    @Column(name = "product_entity_id")
    private UUID productEntityId;

    @Column(name = "quantity")
    private BigDecimal quantity;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module's envelope factory; this replica stores it verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
