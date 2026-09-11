package com.positivity.securityservice.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * A one-time activation token for a tenant's first administrator (ADR-0062 §7, plan WS2b-3,
 * decided 2026-09-10). Provisioning leaves the administrator credential-expired; a platform
 * operator mints one of these and hands it over out of band; {@code POST /v1/auth/activate}
 * exchanges it for the first password.
 *
 * <p>Global table: the activation request is unauthenticated and binds no tenant, so the row is
 * found by {@link #tokenHash} alone and the service then binds {@link #tenantId} for the user
 * update. Only the SHA-256 of the token is ever stored.
 */
@Entity
@TenantGlobal(
        reason = "looked up by hash on the unauthenticated activation request before any tenant is bound; "
                + "tenant_id is carried as data and bound by the service")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "user_activation_tokens")
public class UserActivationToken {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /** The user's tenant, carried as plain data (ADR-0062 §3): never a discriminator here. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Lower-case hex SHA-256 of the token; the token itself is returned once and never stored. */
    @Column(name = "token_hash", nullable = false, updatable = false, length = 64, unique = true)
    @ToString.Exclude
    private String tokenHash;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** When the token was consumed, or closed because a newer one was minted; {@code null} while open. */
    @Column(name = "used_at")
    private Instant usedAt;

    @Column(name = "created_by", nullable = false, updatable = false)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Open, unexpired and therefore exchangeable at {@code now}. */
    public boolean isExchangeableAt(Instant now) {
        return usedAt == null && expiresAt.isAfter(now);
    }
}
