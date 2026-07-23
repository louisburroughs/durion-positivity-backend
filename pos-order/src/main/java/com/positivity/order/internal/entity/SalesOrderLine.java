package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "sales_order_line")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "order")
@EqualsAndHashCode(exclude = "order")
public class SalesOrderLine {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID orderLineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "order_id", nullable = false)
    private SalesOrder order;

    @Column(nullable = false)
    private String itemSku;

    @Column(nullable = false)
    private String itemDescription;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FulfillmentStatus fulfillmentStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PriceSource priceSource;

    @Column
    private String reasonCode;

    @Enumerated(EnumType.STRING)
    @Column
    private SourceType sourceType;

    @Column
    private String sourceId;

    @Column
    private String sourceLineId;

    /** Client-supplied stable line identity for idempotent add-item replays (plan story I1). */
    @Column(columnDefinition = "UUID", updatable = false)
    private UUID clientLineUuid;

    /** Optional line-level discount percent (0-100, 2dp); drives {@code discountAmount}. */
    @Column(precision = 5, scale = 2)
    private BigDecimal discountPercent;

    /** Line-level discount value plus this line's pro-rata share of the order discount. */
    @Column(nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    /** Extended amount post-line-discount, pre-order-discount, pre-tax. */
    @Column(nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal lineSubtotal = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal taxAmount = BigDecimal.ZERO;

    /** lineSubtotal − order-discount allocation + taxAmount. */
    @Column(nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal lineTotal = BigDecimal.ZERO;

    @Column(length = 1000)
    private String customerNote;

    @Column(length = 1000)
    private String internalNote;

    /** pos-price rule breakdown JSON for the applied unit price (audit; parity story B1). */
    @Column(columnDefinition = "text")
    private String pricingBreakdown;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;
}
