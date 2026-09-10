package com.positivity.tenant.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import com.positivity.tenant.internal.enums.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
 * A row of the master tenant registry (ADR-0062 §1, §7). {@code id} is the tenant id every other
 * module's {@code tenant_id} column refers to; the inherited {@code tenant_id} column is the platform
 * tenant that owns the registry row, and the two differ on every row but the platform tenant's own.
 *
 * <p>The platform tenant's own row is written by the bootstrap seed migration under the reserved
 * constant id; every other row gets a UUID v7 on persist.
 */
@Entity
@Table(name = "tenant")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, length = 63)
    private String slug;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private TenantStatus status = TenantStatus.PENDING;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    /** Cell or region the tenant is served from (ADR-0062 §10); free text until cells are modelled. */
    @Column(length = 64)
    private String cell;

    /** Email of the first administrator; provisioning input carried on {@code tenant.created}. */
    @Column(name = "initial_admin_email", nullable = false, length = 320)
    private String initialAdminEmail;

    @Version
    @Column(nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "decommissioned_at")
    private Instant decommissionedAt;
}
