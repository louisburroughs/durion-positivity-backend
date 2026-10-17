package com.positivity.accounting.internal.entity;

import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * another body under the same id is refused (409 {@code IDEMPOTENCY_CONFLICT}). The id is the caller's, so it is
 * assigned, not generated.
 */
@Entity
@Table(name = "ap_payment_allocation_request")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class APPaymentAllocationRequest extends TenantScopedEntity {

    @Id
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
