package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One returned line referencing a sold line on the original order (parity story F1, issue #1086).
 * {@code returnQty} is positive; the cap invariant (Σ returnQty ≤ sold qty per
 * {@code originalOrderLineId} across non-cancelled returns) is enforced at creation.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "return_order_line")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReturnOrderLine {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "return_line_id", columnDefinition = "UUID")
    private UUID returnLineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_order_id", nullable = false)
    @ToString.Exclude
    private ReturnOrder returnOrder;

    /** The sold line (sales_order_line.order_line_id) this line returns against. */
    @Column(name = "original_order_line_id", nullable = false, columnDefinition = "UUID")
    private UUID originalOrderLineId;

    @Column(name = "item_sku", nullable = false)
    private String itemSku;

    @Column(name = "return_qty", nullable = false)
    private int returnQty;

    @Enumerated(EnumType.STRING)
    @Column(name = "condition_code", nullable = false, length = 16)
    private ReturnCondition condition;

    /** Pro-rata refund for this line (per-unit original line total × returnQty, tax included). */
    @Column(name = "line_refund", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineRefund;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "return_order_line_serial", joinColumns = @JoinColumn(name = "return_line_id"))
    @OrderColumn(name = "serial_index")
    @Column(name = "serial_number", nullable = false, length = 128)
    @Builder.Default
    private List<String> serialNumbers = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
