package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "purchase_order_line")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class PurchaseOrderLineEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID lineId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrderEntity purchaseOrder;

    @Column(nullable = false)
    private Integer lineNumber;

    private UUID skuId;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false, precision = 18, scale = 6)
    private BigDecimal quantityDecimal;

    @Column(nullable = false)
    private Long unitCostMinor;

    @Column(nullable = false)
    private Long lineTotalMinor;

    private Long taxMinor;

    private String taxCodeId;

    private String glAccountId;

    @Column(precision = 18, scale = 6)
    private BigDecimal openQuantityDecimal;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
