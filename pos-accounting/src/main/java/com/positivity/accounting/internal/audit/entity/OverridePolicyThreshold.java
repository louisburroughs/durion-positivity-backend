package com.positivity.accounting.internal.audit.entity;

import java.time.Clock;

import jakarta.persistence.*;
import com.positivity.shared.id.UUIDv7Generator;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import com.positivity.shared.id.UUIDv7Id;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
/**
 * Configuration for role-based price override thresholds.
 * 
 * <p>
 * Defines authorization limits by role including absolute amounts
 * and percentage discounts. Supports versioning via effective dates.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "override_policy_threshold", indexes = {
        @Index(name = "idx_role_effective", columnList = "role, effective_date")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OverridePolicyThreshold {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "policy_id", updatable = false, nullable = false, columnDefinition = "UUID")
    private UUID policyId;

    @PrePersist
    public void onPrePersist() {
        if (effectiveDate == null) {
            effectiveDate = Instant.now(Clock.systemUTC());
        }
    }

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
    @CreatedDate
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    @LastModifiedDate
    private Instant updatedAt;
}
