package com.positivity.accounting.audit.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Configuration for role-based price override thresholds.
 * 
 * <p>Defines authorization limits by role including absolute amounts
 * and percentage discounts. Supports versioning via effective dates.
 */
@Entity
@Table(name = "override_policy_threshold", indexes = {
    @Index(name = "idx_role_effective", columnList = "role, effective_date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OverridePolicyThreshold {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "policy_id", updatable = false, nullable = false)
    private UUID policyId;
    
    /**
     * Role this policy applies to (e.g., SERVICE_WRITER, MANAGER, GLOBAL_ADMIN).
     */
    @Column(name = "role", nullable = false, length = 100)
    private String role;
    
    /**
     * Maximum absolute discount amount allowed.
     */
    @Column(name = "max_absolute_amount", precision = 19, scale = 4)
    private BigDecimal maxAbsoluteAmount;
    
    /**
     * Maximum percentage discount allowed.
     */
    @Column(name = "max_percent_off", precision = 5, scale = 2)
    private BigDecimal maxPercentOff;
    
    /**
     * When this policy version becomes effective.
     */
    @Column(name = "effective_date", nullable = false)
    private Instant effectiveDate;
    
    /**
     * When this policy version expires (null = no expiration).
     */
    @Column(name = "expiration_date")
    private Instant expirationDate;
    
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
        if (effectiveDate == null) {
            effectiveDate = now;
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }
}
