package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * One frozen per-jurisdiction row of a {@link TaxLiabilitySnapshot} (issue #998). Immutable after
 * insert. {@code rowOrder} preserves the report's deterministic ordering (state → county → city →
 * special, then code). {@code exemptionReasons} stores the report row's ascending distinct reason
 * codes comma-joined ({@code ""} when none).
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "snapshot")
@Entity
@Table(
        name = "tax_liability_snapshot_row",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_tax_liability_snapshot_row_order",
                    columnNames = {"snapshot_id", "row_order"})
        },
        indexes = {@Index(name = "idx_tax_liability_snapshot_row_snapshot", columnList = "snapshot_id, row_order")})
public class TaxLiabilitySnapshotRow {

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "row_id", nullable = false, columnDefinition = "UUID")
    private UUID rowId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false)
    private TaxLiabilitySnapshot snapshot;

    @Column(name = "row_order", nullable = false)
    private int rowOrder;

    @Column(name = "jurisdiction_type", length = 32, nullable = false)
    private String jurisdictionType;

    @Column(name = "jurisdiction_code", length = 64, nullable = false)
    private String jurisdictionCode;

    @Column(name = "jurisdiction_name", length = 255)
    private String jurisdictionName;

    @Column(name = "taxable_base", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxableBase;

    @Column(name = "exempt_base", nullable = false, precision = 19, scale = 4)
    private BigDecimal exemptBase;

    @Column(name = "exemption_reasons", length = 1000, nullable = false)
    private String exemptionReasons = "";

    @Column(name = "tax_collected_gross", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxCollectedGross;

    @Column(name = "credits_netted", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditsNetted;

    @Column(name = "net_tax", nullable = false, precision = 19, scale = 4)
    private BigDecimal netTax;
}
