package com.positivity.securityservice.internal.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

@Data
@NoArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "users")
public class User extends TenantScopedEntity {
    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
    private String password;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "account_non_locked", nullable = false)
    private boolean accountNonLocked = true;

    @Column(name = "account_non_expired", nullable = false)
    private boolean accountNonExpired = true;

    @Column(name = "credentials_non_expired", nullable = false)
    private boolean credentialsNonExpired = true;

    /**
     * The first-administrator state provisioning creates (ADR-0062 §7, WS2b-3): a discarded password,
     * expired credentials and this marker, which is the only thing an activation token may act on.
     * Cleared by activation and by any ordinary password set; an administrator expiring a live
     * account's credentials never sets it.
     */
    @Column(name = "awaiting_activation", nullable = false)
    private boolean awaitingActivation = false;

    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "last_failed_login_at")
    private Instant lastFailedLoginAt;

    @Column(name = "last_successful_login_at")
    private Instant lastSuccessfulLoginAt;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    @Column(name = "disabled_by", length = 255)
    private String disabledBy;

    @Column(name = "account_expires_at")
    private Instant accountExpiresAt;

    @Column(name = "credentials_expire_at")
    private Instant credentialsExpireAt;

    @Column(name = "person_id")
    private UUID personId;

    @Column(name = "last_login_ip", length = 255)
    private String lastLoginIp;

    @Column(name = "last_login_user_agent", length = 512)
    private String lastLoginUserAgent;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
