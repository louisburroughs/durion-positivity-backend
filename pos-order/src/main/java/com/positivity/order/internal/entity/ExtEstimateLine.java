package com.positivity.order.internal.entity;

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
 * Read-only estimate item replica (parity story E1, spec R7.1): full replacement set on every
 * {@code workorder.estimate.updated} snapshot. Only APPROVED, non-deleted items are contractual
 * for import — the approval price is never repriced.
 */
@Entity
@Table(name = "ext_estimate_line")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtEstimateLine {

    @Id
    @Column(name = "item_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID itemId;

    @Column(name = "estimate_id", nullable = false, columnDefinition = "UUID")
    private UUID estimateId;

    /** Owner {@code EstimateItemType} name: PART or LABOR. */
    @Column(length = 16)
    private String itemType;

    @Column(length = 512)
    private String description;

    @Column(precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(precision = 19, scale = 4)
    private BigDecimal lineTotal;

    @Column(columnDefinition = "UUID")
    private UUID productId;

    @Column(columnDefinition = "UUID")
    private UUID serviceId;

    @Column(length = 32)
    private String approvalStatus;

    @Column
    private Boolean deleted;

    /** ArchUnit UUIDv7 rule hook: itemId is a UUIDv7 issued by pos-workorder. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
