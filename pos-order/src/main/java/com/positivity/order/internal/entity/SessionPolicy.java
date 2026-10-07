package com.positivity.order.internal.entity;

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
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The drawer policy of one tenant (CAP:550 S16, #2512; SPEC-accounting-workspace §4.6 "Drawer
 * limits", AW19): whether petty expenses and vendor cash on delivery are allowed and their cashier
 * limits, and the over/short tolerance above which a close needs {@code order:session:approve_variance}.
 * Amounts are in the tenant's functional currency. One row per tenant; while there is none the
 * defaults apply ({@code SessionPolicyServiceImpl}). The version makes racing PUTs fail one of them.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "session_policy")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionPolicy extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "session_policy_id", columnDefinition = "UUID")
    private UUID sessionPolicyId;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "petty_expense_allowed", nullable = false)
    private boolean pettyExpenseAllowed;

    @Column(name = "petty_expense_limit", precision = 19, scale = 4)
    private BigDecimal pettyExpenseLimit;

    @Column(name = "vendor_cod_allowed", nullable = false)
    private boolean vendorCodAllowed;

    @Column(name = "vendor_cod_limit", precision = 19, scale = 4)
    private BigDecimal vendorCodLimit;

    @Column(name = "over_short_tolerance", nullable = false, precision = 19, scale = 4)
    private BigDecimal overShortTolerance;

    /** Who last changed it (ADR-0018). */
    @Column(name = "updated_by", nullable = false)
    private String updatedBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
