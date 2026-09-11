package com.positivity.securityservice.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * JPA entity for storing JWT tokens in the database.
 *
 * **Purpose:**
 * - Persistence record of issued tokens for validation
 * - Consistency check during token validation
 * - Revocation tracking (paired with Redis cache)
 *
 * **Concurrency:**
 * - Uses @Version for optimistic locking
 * - Prevents concurrent updates during token revocation
 * - Throws ObjectOptimisticLockingFailureException on conflict
 * - JwtService handles retries with exponential backoff
 *
 * **Storage:**
 * - token: Access token (unique)
 * - refreshToken: Refresh token (unique; null for an impersonation token, which has no
 *   refresh half — ADR-0062 §7, WS2b-4)
 * - subject: Token subject (username or principal)
 * - impersonatedByUserId: the platform operator behind an impersonation token; null otherwise
 * - issuedAt: Token creation timestamp
 * - expiresAt: Access token expiration
 * - refreshExpiresAt: Refresh token expiration
 *
 * @since 1.0
 */
@Data
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
public class JwtToken extends TenantScopedEntity {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, unique = true, columnDefinition = "TEXT")
    private String token;

    /** {@code null} for an impersonation token: it is never refreshable (WS2b-4). */
    @Column(unique = true, columnDefinition = "TEXT")
    private String refreshToken;

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    /** {@code null} whenever {@link #refreshToken} is. */
    private Instant refreshExpiresAt;

    @Column(nullable = false)
    private String subject;

    /**
     * The platform operator who minted this impersonation token (ADR-0062 §7, WS2b-4);
     * {@code null} on every ordinary token.
     *
     * <p>An impersonation row is stored in the <em>target</em> tenant under a synthetic subject
     * ({@code support:<operator>@<slug>}), so neither its {@code tenant_id} nor its {@code subject}
     * names the operator. This column is the revocable link back to them: it is what {@code
     * ImpersonationTokenRevocationService} sweeps on when the operator is disabled, expired, or
     * loses the platform role, none of which can reach the row through {@code
     * findAllBySubject(username)} under the platform binding.
     */
    @Column(name = "impersonated_by_user_id", columnDefinition = "UUID")
    private UUID impersonatedByUserId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Version field for optimistic locking.
     *
     * **Concurrency Handling:**
     * - Incremented by JPA on each update
     * - Used to detect concurrent modifications
     * - Throws ObjectOptimisticLockingFailureException on version mismatch
     * - Example retry pattern: 3 attempts, 100ms base delay, 2x multiplier
     *
     * **Common Scenario:**
     * Thread A and Thread B both attempt to revoke the same token:
     * 1. Thread A loads JwtToken (version=1)
     * 2. Thread B loads JwtToken (version=1)
     * 3. Thread A revokes, saves (version incremented to 2)
     * 4. Thread B revokes, save fails with ObjectOptimisticLockingFailureException
     * 5. Thread B retries, loads fresh version (version=2), succeeds
     */
    @Version
    private Long version;
}
