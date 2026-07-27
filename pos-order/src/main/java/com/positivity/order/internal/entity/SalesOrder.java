package com.positivity.order.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "sales_order")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesOrder {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID orderId;

    @Version
    @Column(nullable = false)
    private Long version;

    /** Human-facing order number, e.g. {@code SO-1A2B3C4D-2607-000042}; assigned at creation. */
    @Column(unique = true, updatable = false)
    private String orderNumber;

    @Column(columnDefinition = "UUID")
    private UUID locationId;

    /** Optional parking label for resumable drafts ("blue F-150 waiting on customer"). */
    @Column
    private String label;

    @Column(columnDefinition = "UUID")
    private UUID customerId;

    @Column(columnDefinition = "UUID")
    private UUID vehicleId;

    /** Set when a customer/vehicle is attached: VALIDATED, or PENDING while CRM is unreachable. */
    @Enumerated(EnumType.STRING)
    @Column
    private CustomerValidationStatus customerValidationStatus;

    /** Client idempotency key from cart creation; unique, replays return the original cart. */
    @Column(updatable = false, length = 128, unique = true)
    private String creationIdempotencyKey;

    @Column(nullable = false)
    private String clerkId;

    @Column(nullable = false)
    private String terminalId;

    /** Register session this order was opened under, when a session was open on the terminal (G1). */
    @Column(name = "session_id", columnDefinition = "UUID")
    private UUID sessionId;

    /** When this order takes a deposit (story E4), the source document type it is held against. */
    @Column(name = "deposit_source_type", length = 16)
    private String depositSourceType;

    /** Source document id the deposit is held against (story E4). */
    @Column(name = "deposit_source_id", columnDefinition = "UUID")
    private UUID depositSourceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SalesOrderStatus status;

    /** Σ lineSubtotal: post-line-discount, pre-order-discount, pre-tax (documented in OpenAPI). */
    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal;

    /** Σ line discounts + the allocated order discount. */
    @Column(nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal taxTotal = BigDecimal.ZERO;

    /** subtotal − order-discount allocation + taxTotal. */
    @Column(nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal grandTotal = BigDecimal.ZERO;

    /** True when line mutations have invalidated {@code taxTotal}; cleared by tax recompute. */
    @Column(nullable = false)
    @Builder.Default
    private boolean taxStale = false;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private OrderDiscountType orderDiscountType;

    @Column(precision = 19, scale = 4)
    private BigDecimal orderDiscountValue;

    @Column(length = 64)
    private String orderDiscountReasonCode;

    @Column(length = 2000)
    private String generalNote;

    /** Quote validity horizon; set on DRAFT→QUOTED, cleared on reopen (parity story A3). */
    @Column
    private Instant quoteExpiresAt;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false, updatable = false)
    private String createdBy;

    @Column(nullable = false)
    private String updatedBy;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<SalesOrderLine> lines = new ArrayList<>();

    @Column(nullable = true)
    private String cancellationReason;

    @Column(nullable = true, columnDefinition = "UUID")
    private UUID workOrderId;

    @Column(nullable = true)
    private String cancellationIdempotencyKey;

    /** Invoice fronting this order, set at checkout (parity story C1). */
    @Column(columnDefinition = "UUID")
    private UUID invoiceId;

    @Column(length = 64)
    private String invoiceNumber;

    /** Checkout idempotency key (spec R9.3); unique, replays return the checked-out order. */
    @Column(length = 128, unique = true)
    private String checkoutIdempotencyKey;

    /** Net settled amount from the payment-record ledger (parity story C3). */
    @Column(nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal amountPaid = BigDecimal.ZERO;

    /** grandTotal − amountPaid, floored at zero; set at checkout, maintained by settlement events. */
    @Column(precision = 19, scale = 4)
    private BigDecimal balanceDue;
}
