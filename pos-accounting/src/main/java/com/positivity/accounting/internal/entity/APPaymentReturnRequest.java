package com.positivity.accounting.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One accepted {@code record-return} request (#2642): the caller's {@code requestId}, the payment it returned and a
 * hash of the body. A replay with the same requestId and body answers the payment; a different body is 409
 * {@code IDEMPOTENCY_CONFLICT}. The {@link APPaymentAllocationRequest} convention.
 */
@Entity
@Table(name = "ap_payment_return_request")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class APPaymentReturnRequest extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "return_request_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID returnRequestId;

    @Column(name = "request_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID requestId;

    @Column(name = "payment_id", nullable = false, updatable = false, columnDefinition = "UUID")
    private UUID paymentId;

    @Column(name = "request_hash", length = 64, nullable = false, updatable = false)
    private String requestHash;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public APPaymentReturnRequest(UUID requestId, UUID paymentId, String requestHash) {
        this.requestId = requestId;
        this.paymentId = paymentId;
        this.requestHash = requestHash;
    }
}
