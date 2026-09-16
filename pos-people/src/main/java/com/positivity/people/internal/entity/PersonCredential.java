package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.CredentialStatus;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * A credential a person holds (CAP-328, spec D6/D7): what it certifies ({@link Skill}), who issued
 * it, when, until when, and where the evidence is. The first-class aggregate — the skill is only
 * the vocabulary. Natural key (person, skill, issuer, issuedOn): a renewal is a new row.
 *
 * <p>{@link #status} is stored for the audit's benefit but derived from the dates on every read
 * through {@link #effectiveStatus}; only REVOKED and SUPERSEDED survive that derivation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "person_credential")
public class PersonCredential extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    /** Who issued it — {@code ASE}, {@code EPA_609}, {@code STATE_TX}, {@code SHOP}. */
    @Column(name = "issuer", nullable = false, length = 32)
    private String issuer;

    /** The vendor code system the credential arrived under, as received. */
    @Column(name = "source_code", length = 32)
    private String sourceCode;

    @Column(name = "source_credential_code", length = 128)
    private String sourceCredentialCode;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    /** Null means it does not expire — never "expired". */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    /** Display metadata only (spec D7): nothing thresholds on it. */
    @Column(name = "proficiency")
    private Integer proficiency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private CredentialStatus status;

    /** Retained evidence (49 CFR 396.19) — a document id in the evidence store. */
    @Column(name = "evidence_ref")
    private UUID evidenceRef;

    @Column(name = "source_system", length = 64)
    private String sourceSystem;

    @Column(name = "source_version", length = 64)
    private String sourceVersion;

    /** The job or event that stopped sending this row, when SUPERSEDED. */
    @Column(name = "superseded_by", length = 128)
    private String supersededBy;

    @Column(name = "created_by", updatable = false)
    private String createdBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The state on {@code onDate}: the dates decide unless the row was revoked or superseded. */
    public @NonNull CredentialStatus effectiveStatus(@NonNull LocalDate onDate) {
        if (status == CredentialStatus.REVOKED || status == CredentialStatus.SUPERSEDED) {
            return status;
        }
        return CredentialStatus.derive(issuedOn, expiresOn, onDate);
    }

    /** Whether the person held this qualification on {@code onDate} (facility-local, DECISION-015). */
    public boolean qualifiedOn(@NonNull LocalDate onDate) {
        return effectiveStatus(onDate) == CredentialStatus.ACTIVE && !issuedOn.isAfter(onDate);
    }
}
