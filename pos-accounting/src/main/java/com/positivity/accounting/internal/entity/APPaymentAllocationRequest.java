package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One {@code POST /v1/accounting/ap/payments/{paymentId}/allocations} request (CAP:550 S17, #2513): the caller's
 * {@code requestId}, the payment it allocated and a hash of its lines, so a replay answers the same payment and
 * another body under the same id is refused (409 {@code IDEMPOTENCY_CONFLICT}). Its own key is generated; the
 * caller's {@code requestId} is unique per tenant (the #2678 convention), so two tenants may reuse one id.
 */
@Entity
@Table(name = "ap_payment_allocation_request")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class APPaymentAllocationRequest extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "allocation_request_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID allocationRequestId;

    @Column(name = "request_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID requestId;

    @Column(name = "payment_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID paymentId;

    @Column(name = "request_hash", length = 64, nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public APPaymentAllocationRequest(UUID requestId, UUID paymentId, String requestHash, Instant createdAt) {
        this.requestId = requestId;
        this.paymentId = paymentId;
        this.requestHash = requestHash;
        this.createdAt = createdAt;
    }
}
