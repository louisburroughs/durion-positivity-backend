package com.positivity.tenant.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A reading tier (CAP:550 S45, #2619; AW63): a code, a display name, a monthly document-reading allowance (an amount
 * with its currency, ADR-0067 PC-3) and an active flag. Platform operators create and change tiers; a tier is never
 * deleted, only deactivated, and only while no non-decommissioned tenant is on it. Every row belongs to the platform
 * tenant (ADR-0062 §7).
 */
@Entity
@Table(name = "reading_tier")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReadingTierEntity extends TenantScopedEntity {

    public static final String STANDARD = "STANDARD";

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /** {@code ^[A-Z][A-Z0-9_]{1,31}$}, unique; never changes. */
    @Column(nullable = false, length = 32, updatable = false)
    private String code;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(name = "monthly_allowance_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal monthlyAllowanceAmount;

    @Column(name = "monthly_allowance_currency", nullable = false, length = 3)
    private String monthlyAllowanceCurrency;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
