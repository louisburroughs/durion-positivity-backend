package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import com.positivity.supplier.internal.enums.RedactionClassification;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
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
 * Capability endpoint binding of a vendor profile (ADR-0050 §3): one capability mapped to
 * {@code (protocolFamily, protocolVersion, baseUrl, path, authConfigName, optional cron
 * schedule, enabled, captureLevel)}. At most one binding per (profile, capability); an absent
 * or disabled binding means the capability is disabled for the profile
 * ({@code CAPABILITY_NOT_CONFIGURED}, never an error leak).
 *
 * <p>{@code protocolVersion} is data, not an enum (ADR-0051 §3). {@code authConfigName}
 * references {@code SupplierAuthConfigEntity#getName()} within the same profile — validated
 * on write at the service layer; deliberately no cross-name DB FK.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "supplier_endpoint_binding",
        indexes = {@Index(name = "idx_sbinding_profile", columnList = "vendor_profile_id")},
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "supplier_endpoint_binding_profile_capability_key",
                    columnNames = {"vendor_profile_id", "capability"})
        })
@EntityListeners(AuditingEntityListener.class)
public class SupplierEndpointBindingEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    /** Owning {@code supplier_profile.vendor_profile_id} (DB FK {@code fk_sbinding_profile}). */
    @Column(name = "vendor_profile_id", nullable = false)
    private UUID vendorProfileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, length = 64)
    private SupplierCapability capability;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol_family", nullable = false, length = 64)
    private ProtocolFamily protocolFamily;

    /** Norm-version key within the family (e.g. {@code C1_1}) — data, not an enum. */
    @Column(name = "protocol_version", nullable = false, length = 64)
    private String protocolVersion;

    @Column(name = "base_url", nullable = false, length = 1024)
    private String baseUrl;

    @Column(name = "path", nullable = false, length = 512)
    private String path;

    /** Name of the profile auth config authenticating this binding (ADR-0050 §4). */
    @Column(name = "auth_config_name", nullable = false, length = 100)
    private String authConfigName;

    /** Cron expression for batch capabilities; {@code null} for on-demand capabilities. */
    @Column(name = "schedule_cron", length = 100)
    private String scheduleCron;

    /** Whether the binding resolves; a disabled binding behaves as absent (ADR-0050 §3). */
    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    /** Exchange-audit payload capture level (ADR-0050 §7); {@code null} = deployment default. */
    @Enumerated(EnumType.STRING)
    @Column(name = "capture_level", length = 32)
    private PayloadCaptureLevel captureLevel;

    /**
     * Data classifications whose named fields a {@code REDACTED} capture of this binding additionally
     * removes (ADR-0050 §7 minimization — redaction configuration lives with the binding; issue #1259).
     * Empty means credential redaction only. Eager because {@code ExchangeAuditWriter} reads the binding
     * row once per audit write and must not depend on the session staying open.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "supplier_endpoint_binding_redaction", joinColumns = @JoinColumn(name = "binding_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "classification", nullable = false, length = 64)
    @Builder.Default
    private Set<RedactionClassification> redactionClassifications = new HashSet<>();

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
