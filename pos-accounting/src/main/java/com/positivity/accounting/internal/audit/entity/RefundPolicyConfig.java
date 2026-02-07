package com.positivity.accounting.internal.audit.entity;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Configuration for refund authorization policies.
 * 
 * <p>Defines how refunds are handled based on payment settlement status
 * and authorization requirements.
 */
@Entity
@Table(name = "refund_policy_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RefundPolicyConfig {
    
    @Id
    @Column(name = "config_id", updatable = false, nullable = false, columnDefinition = "UUID")
    private UUID configId;

    @PrePersist
    public void generateId() {
        if (configId == null) {
            configId = UUIDv7Generator.generate();
        }
    }
    
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
    private Instant createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    
    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
