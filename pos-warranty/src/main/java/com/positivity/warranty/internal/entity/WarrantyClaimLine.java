package com.positivity.warranty.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.warranty.internal.enums.LineDisposition;
import com.positivity.warranty.internal.enums.LineSourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One claim line per failed part or service (PRD §3.5). Provenance follows the
 * SalesOrderLine pattern ({@code sourceType}/{@code sourceId}/{@code sourceLineId});
 * {@code sku}/{@code description}/{@code originalUnitPrice} are snapshots frozen at intake.
 * Tread depths are integers in 32nds of an inch (PRD §10).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "warranty_claim_line", indexes = @Index(name = "idx_wline_claim", columnList = "claim_id"))
@EntityListeners(AuditingEntityListener.class)
public class WarrantyClaimLine {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "claim_id", nullable = false)
    private WarrantyClaim claim;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private LineSourceType sourceType;

    /** Originating invoice or workorder id (null for MANUAL lines). */
    @Column(name = "source_id")
    private UUID sourceId;

    /** Originating invoice/workorder line id (null for MANUAL lines). */
    @Column(name = "source_line_id")
    private UUID sourceLineId;

    @Column(name = "product_entity_id")
    private UUID productEntityId;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "description", length = 512)
    private String description;

    @Column(name = "serial_number", length = 100)
    private String serialNumber;

    /** Tire DOT number. */
    @Column(name = "dot_number", length = 20)
    private String dotNumber;

    @Builder.Default
    @Column(name = "quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "original_unit_price", precision = 19, scale = 4)
    private BigDecimal originalUnitPrice;

    /** Original tread depth in 32nds of an inch. */
    @Column(name = "original_tread_depth")
    private Integer originalTreadDepth;

    /** Measured remaining tread depth in 32nds of an inch. */
    @Column(name = "measured_tread_depth")
    private Integer measuredTreadDepth;

    /** Computed credit fraction in [0, 1] (PRD §6). */
    @Column(name = "proration_pct", precision = 9, scale = 6)
    private BigDecimal prorationPct;

    /** Every number that fed the proration computation, frozen for audit (PRD §3.5). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proration_inputs", columnDefinition = "jsonb")
    private Map<String, Object> prorationInputs;

    @Column(name = "amount_requested", precision = 19, scale = 4)
    private BigDecimal amountRequested;

    @Column(name = "amount_approved", precision = 19, scale = 4)
    private BigDecimal amountApproved;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "line_disposition", length = 32)
    private LineDisposition lineDisposition = LineDisposition.PENDING;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;
}
