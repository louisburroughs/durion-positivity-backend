package com.positivity.accounting.internal.entity;

import com.positivity.accounting.internal.enums.MatchConfidence;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only evidence of one match of a vendor bill (#2509; SPEC-accounting-workspace §4.3, P3): written when a
 * {@code /match} changes the bill's status and when a candidate selection picks it, never updated. It keeps what
 * the decision rests on: the score and the points per criterion (amount 40, products 30, date 20, purchase order
 * 5), the confidence, the invoice, the received and billed totals, and the line-by-line comparison the tolerance
 * check made.
 */
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
@Entity
@Immutable
@Table(name = "vendor_bill_match_evidence")
public class VendorBillMatchEvidence extends TenantScopedEntity {

    /** Where the evidence came from. */
    public enum Source {
        /** A {@code /match} that changed the bill's status. */
        MATCH,
        /** A person picking this bill among the candidates of an ambiguous match. */
        CANDIDATE_SELECTION
    }

    /** Keys of one {@link #lineComparison} entry. */
    public static final String LINE_NUMBER = "lineNumber";

    public static final String PRODUCT_ID = "productId";
    public static final String RECEIVED_QUANTITY = "receivedQuantity";
    public static final String RECEIVED_UNIT_PRICE = "receivedUnitPrice";
    public static final String BILLED_QUANTITY = "billedQuantity";
    public static final String BILLED_UNIT_PRICE = "billedUnitPrice";
    public static final String QUANTITY_WITHIN_TOLERANCE = "quantityWithinTolerance";
    public static final String PRICE_WITHIN_TOLERANCE = "priceWithinTolerance";

    @EqualsAndHashCode.Include
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "match_evidence_id", nullable = false, columnDefinition = "UUID")
    private UUID matchEvidenceId;

    @Column(name = "vendor_bill_id", nullable = false, updatable = false)
    private UUID vendorBillId;

    /** The invoice event that was matched. */
    @Column(name = "invoice_event_id", nullable = false, updatable = false)
    private UUID invoiceEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 30, nullable = false, updatable = false)
    private Source source;

    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", length = 30, nullable = false, updatable = false)
    private MatchConfidence confidence;

    /** The total score, 0-95: the sum of the four points below. */
    @Column(name = "score", nullable = false, updatable = false)
    private int score;

    @Column(name = "amount_points", nullable = false, updatable = false)
    private int amountPoints;

    @Column(name = "product_points", nullable = false, updatable = false)
    private int productPoints;

    @Column(name = "date_points", nullable = false, updatable = false)
    private int datePoints;

    @Column(name = "purchase_order_points", nullable = false, updatable = false)
    private int purchaseOrderPoints;

    @Column(name = "invoice_reference", length = 50, nullable = false, updatable = false)
    private String invoiceReference;

    @Column(name = "invoice_date", nullable = false, updatable = false)
    private LocalDateTime invoiceDate;

    /** The bill's date before the match: the receipt date (AW46); the bill takes the invoice date. */
    @Column(name = "received_date", nullable = false, updatable = false)
    private LocalDateTime receivedDate;

    /** What the receipt put on the bill before the match: the received lines' total. */
    @Column(name = "received_total", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal receivedTotal;

    /** What the vendor billed. */
    @Column(name = "billed_total", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal billedTotal;

    /** ISO 4217 code of both totals and every price (ADR-0067 R-1): the bill's, the ledger currency. */
    @Column(name = "currency_code", length = 3, nullable = false, updatable = false)
    private String currencyCode;

    /** Whether every compared line and the total were within the quantity and price tolerances. */
    @Column(name = "within_tolerance", nullable = false, updatable = false)
    private boolean withinTolerance;

    /** One entry per compared line, keyed by the constants above. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "line_comparison", nullable = false, updatable = false)
    private List<Map<String, Object>> lineComparison;

    @Column(name = "recorded_by", length = 50, nullable = false, updatable = false)
    private String recordedBy;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;
}
