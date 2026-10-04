package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@Table(name = "goods_receipt")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EntityListeners(AuditingEntityListener.class)
public class GoodsReceiptEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID receiptId;

    @Column(nullable = false)
    private String receiptNumber;

    /**
     * The purchase order this belongs to, by id (CAP-320 #1334).
     *
     * <p>A plain identifier rather than a JPA association: the order lives in pos-order now, so
     * there is nothing here to join to. What the order says is read from the
     * {@code ext_purchase_order} projection, which is the cross-domain read path (ADR-0044 R3).
     */
    @Column(name = "po_id", nullable = false)
    private UUID purchaseOrderId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "asn_id")
    private AdvanceShippingNoticeEntity asn;

    @Column(nullable = false)
    private UUID locationId;

    private Long totalAccruedAmountMinor;

    /** The receiving session this receipt was recorded from (#2455); null for a sessionless receipt. */
    @Column(name = "receiving_session_id")
    private UUID receivingSessionId;

    /** The operation within the session the idempotency key applies to: RECEIVE or CROSS_DOCK:lineId. */
    @Column(name = "idempotency_scope", length = 80)
    private String idempotencyScope;

    /** The caller's Idempotency-Key, or a server-generated one (#2455). */
    @Column(name = "idempotency_key")
    private String idempotencyKey;

    /** SHA-256 of the canonical request, to tell a replay from a key reused for another request. */
    @Column(name = "request_fingerprint", length = 64)
    private String requestFingerprint;

    /** The JSON response the original call returned, replayed on a retry. */
    @Column(name = "response_snapshot", columnDefinition = "text")
    private String responseSnapshot;

    @CreatedBy
    @Column(nullable = false, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "goodsReceipt", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<GoodsReceiptLineEntity> lines = new ArrayList<>();
}
