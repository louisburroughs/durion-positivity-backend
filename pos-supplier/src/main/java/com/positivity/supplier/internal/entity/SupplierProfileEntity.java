package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.enums.ProfileSourceOfTruth;
import com.positivity.supplier.internal.enums.RetryBackoff;
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
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Vendor profile — one supplier account in one deployment (ADR-0050 §2): identity
 * ({@code vendorProfileId} UUIDv7, ADR-0050 §1), display name, protocol defaults, sandbox
 * overlay, and the {@code sourceOfTruth} marker (ADR-0050 §6). Child collections (auth
 * configs, commercial accounts, endpoint bindings) reference this row by
 * {@code vendorProfileId}.
 *
 * <p>{@code supplierRef} is a unique human-readable configuration alias (YAML key, logs,
 * admin screens) — never an identifier crossing a contract boundary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "supplier_profile")
@EntityListeners(AuditingEntityListener.class)
public class SupplierProfileEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "vendor_profile_id", updatable = false, nullable = false)
    private UUID vendorProfileId;

    @Column(name = "supplier_ref", nullable = false, unique = true, length = 100)
    private String supplierRef;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    /** Whether the profile's bindings resolve at all (ADR-0050 §3). */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Whether the profile runs against the vendor's sandbox environment (ADR-0050 §2). */
    @Column(name = "sandbox", nullable = false)
    private boolean sandbox;

    /** Sandbox overlay base URL (architecture doc §7 {@code sandbox.baseUrlOverride}). */
    @Column(name = "sandbox_base_url_override", length = 1024)
    private String sandboxBaseUrlOverride;

    /** Default connect timeout for the profile's bindings; {@code null} = deployment default. */
    @Column(name = "connect_timeout_ms")
    private Integer connectTimeoutMs;

    /** Default read timeout for the profile's bindings; {@code null} = deployment default. */
    @Column(name = "read_timeout_ms")
    private Integer readTimeoutMs;

    /** Default pre-send retry budget; {@code null} = deployment default. */
    @Column(name = "retry_max_attempts")
    private Integer retryMaxAttempts;

    /** Retry backoff strategy; {@code null} = deployment default. */
    @Enumerated(EnumType.STRING)
    @Column(name = "retry_backoff", length = 32)
    private RetryBackoff retryBackoff;

    /**
     * Authoritative configuration source (ADR-0050 §6): {@code YAML}-managed profiles are
     * reconciled on every startup and reject admin mutations; {@code ADMIN}-managed profiles
     * are untouched by YAML reconciliation.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source_of_truth", nullable = false, length = 16)
    private ProfileSourceOfTruth sourceOfTruth;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private String updatedBy;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
}
