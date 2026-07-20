package com.positivity.tax.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tax.common.enums.ExemptionReasonCode;
import com.positivity.tax.internal.enums.ExemptionCertificateStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Tax exemption certificate registry entry (story T3, decision D-T1).
 * <p>
 * pos-tax owns this small table because an exemption is tax semantics, not CRM
 * identity. The calculate path stays a pure function of request + config + a registry
 * lookup: given a {@code customerId} (or a line/request certificate id), an active,
 * effective, non-expired certificate drives an honored exemption; otherwise a claimed
 * exemption is denied and the line taxed anyway (D-T2).
 * <p>
 * Audit timestamps are maintained by JPA lifecycle callbacks so persistence works
 * without Spring Data auditing infrastructure (this is a utility service with no user
 * security context on the calculate path).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "exemption_certificate")
public class ExemptionCertificate {

    /** UUID v7 primary key (ADR-0013). */
    @Id
    @UUIDv7Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Customer this certificate belongs to (matches {@code TaxCalculationRequest.customerId}). */
    @Column(name = "customer_id", nullable = false, length = 100)
    private String customerId;

    /**
     * State/region scope this certificate applies to (subdivision code, e.g. CA). When
     * {@code null}, the certificate applies in any state.
     */
    @Column(name = "state_scope", length = 10)
    private String stateScope;

    /** Exemption reason this certificate documents. */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 32)
    private ExemptionReasonCode reasonCode;

    /** Optional external/paper certificate number. */
    @Column(name = "certificate_number", length = 100)
    private String certificateNumber;

    /** Inclusive date on which the certificate becomes effective. */
    @Column(name = "effective_from", nullable = false)
    private LocalDate effectiveFrom;

    /** Inclusive expiry date; {@code null} means no expiry. */
    @Column(name = "expires_at")
    private LocalDate expiresAt;

    /** Lifecycle status. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ExemptionCertificateStatus status;

    /** Creation timestamp (ADR-0018/0024). */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Last-modification timestamp (ADR-0018/0024). */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
