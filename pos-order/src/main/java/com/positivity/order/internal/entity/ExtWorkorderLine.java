package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Read-only workorder line replica (parity story E1, spec R7.1): full replacement set on every
 * {@code workorder.workorder.updated} snapshot. Source-document import reads non-declined lines
 * priced-as-approved; the explicit {@code returnable} flag is carried per resolved Q6.
 */
@Entity
@Table(name = "ext_workorder_line")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtWorkorderLine {

    public enum LineKind {
        PART,
        SERVICE
    }

    @Id
    @Column(name = "line_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID lineId;

    @Column(name = "workorder_id", nullable = false, columnDefinition = "UUID")
    private UUID workorderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_kind", nullable = false, length = 16)
    private LineKind lineKind;

    /** pos-catalog product reference (PART lines; null for labor). */
    @Column(columnDefinition = "UUID")
    private UUID productId;

    @Column(length = 512)
    private String description;

    @Column(precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(precision = 19, scale = 4)
    private BigDecimal lineTotal;

    @Column
    private Boolean declined;

    @Column
    private Boolean returnable;

    /** ArchUnit UUIDv7 rule hook: lineId is a UUIDv7 issued by pos-workorder. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
