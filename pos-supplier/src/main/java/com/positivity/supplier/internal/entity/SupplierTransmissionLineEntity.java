package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One line of a transmission intent, as it will be (or was) sent to the vendor.
 *
 * <h2>Why the lines are persisted at all</h2>
 *
 * A command arrives, an intent is minted, and dispatch happens later — possibly after a restart,
 * possibly after a circuit breaker closes. The document has to be reconstructible at that point
 * from state that committed with the intent, or a re-dispatch would either need the original
 * Kafka message back or would send a different order under the same document id. Both are worse
 * than a small table.
 *
 * <p>These rows are the <em>transmission</em> record, not a replica of the purchase order.
 * pos-supplier does not own the aggregate (ADR-0049 §2) and nothing here is updated when the
 * ordering domain changes its mind: a changed order is a new revision and therefore a new intent
 * with its own lines and its own document id.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "supplier_transmission_line",
        indexes = {@Index(name = "idx_stline_intent", columnList = "transmission_intent_id, line_number")})
public class SupplierTransmissionLineEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "line_id", updatable = false, nullable = false)
    private UUID lineId;

    @Column(name = "transmission_intent_id", nullable = false, updatable = false)
    private UUID transmissionIntentId;

    /** 1-based; becomes the zero-padded wire {@code LineID} the vendor echoes back on status. */
    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "article_ean", length = 64)
    private String articleEan;

    /** The vendor's own article code. A display alias, never an identifier (ADR-0013/0027). */
    @Column(name = "supplier_article_code", length = 64)
    private String supplierArticleCode;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    /**
     * The delivery date asked for on this line. Stays the <em>requested</em> date forever — what
     * the vendor confirms arrives separately on the status feed and is never written back here
     * (#1318).
     */
    @Column(name = "requested_delivery_date")
    private LocalDate requestedDeliveryDate;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
