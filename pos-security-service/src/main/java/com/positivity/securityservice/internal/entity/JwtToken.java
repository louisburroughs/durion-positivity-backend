package com.positivity.securityservice.internal.entity;

import java.time.Clock;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

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
 * - token: Access token (unique, nullable if refresh-only)
 * - refreshToken: Refresh token (unique, nullable if access-only)
 * - subject: Token subject (username or principal)
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
public class JwtToken {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false, unique = true, length = 512)
    private String token;

    @Column(nullable = false, unique = true, length = 512)
    private String refreshToken;

    @Column(nullable = false)
    private Instant issuedAt;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column(nullable = false)
    private Instant refreshExpiresAt;

    @Column(nullable = false)
    private String subject;

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
