package com.positivity.accounting.internal.audit.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Configuration for refund authorization policies.
 *
 * <p>
 * Defines how refunds are handled based on payment settlement status
 * and authorization requirements.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "refund_policy_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundPolicyConfig {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "config_id", updatable = false, nullable = false, columnDefinition = "UUID")
    private UUID configId;
    /**
     * Whether refund requires separate authorization from original sale.
     */
    @Column(name = "requires_separate_authorization", nullable = false)
    @Builder.Default
    private Boolean requiresSeparateAuthorization = true;

    /**
     * How to handle settled payments (CREDIT_MEMO or REFUND_PAYMENT).
     */
    @Column(name = "settled_payment_handling", nullable = false, length = 50)
    @Builder.Default
    private String settledPaymentHandling = "CREDIT_MEMO";

    /**
     * How to handle unsettled payments (typically REVERSAL).
     */
    @Column(name = "unsettled_payment_handling", nullable = false, length = 50)
    @Builder.Default
    private String unsettledPaymentHandling = "REVERSAL";

    /**
     * Version identifier for this policy.
     */
    @Column(name = "version", nullable = false, length = 50)
    private String version;

    /**
     * Is this policy currently active?
     */
    @Column(name = "active", nullable = false)
    @Builder.Default
    private Boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreatedDate
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @LastModifiedDate
    private Instant updatedAt;
}
