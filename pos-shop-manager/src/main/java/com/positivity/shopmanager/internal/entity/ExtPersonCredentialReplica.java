package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.shopmanager.internal.enums.CredentialStatus;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * Read-only replica of a person's credential, fed by {@code people.events.v1}
 * ({@code PersonCredentialUpdatedV1}; CAP-328, ADR-0044 §6). pos-people owns the aggregate; only
 * {@link com.positivity.shopmanager.internal.service.PeopleEventsListener} writes this table.
 *
 * <p>A renewal is a separate credential (and so a separate row) in the owner, so the history a
 * DOT auditor asks about survives here too. {@link #status} is stored as received, but expiry is
 * judged on read via {@link #statusOn(LocalDate)} — the feed's view of "today" is not the
 * facility's (DECISION-SHOPMGMT-015).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_person_credential")
public class ExtPersonCredentialReplica extends TenantScopedEntity {

    @Id
    @Column(name = "credential_id", nullable = false)
    private UUID credentialId;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    /** Durion skill code from the registry (e.g. {@code BRAKES-MEDIUM_HEAVY}). */
    @Column(name = "skill_code", nullable = false, length = 64)
    private String skillCode;

    @Column(name = "competence_code", nullable = false, length = 64)
    private String competenceCode;

    @Column(name = "min_gvwr_class", nullable = false)
    private int minGvwrClass;

    @Column(name = "max_gvwr_class", nullable = false)
    private int maxGvwrClass;

    @Column(name = "issuer", nullable = false, length = 32)
    private String issuer;

    @Column(name = "source_code", length = 32)
    private String sourceCode;

    /** The issuer's own code for the credential (e.g. ASE {@code T4-BRAKES}), as received. */
    @Column(name = "source_credential_code", length = 128)
    private String sourceCredentialCode;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    /** Null means the credential never expires — not that it has. */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    /** Display metadata only (CAP-328 D7); no requirement thresholds on it. */
    @Column(name = "proficiency")
    private Integer proficiency;

    /** Status as received; read through {@link #statusOn(LocalDate)}. */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "evidence_ref")
    private UUID evidenceRef;

    @Column(name = "superseded_by", length = 128)
    private String supersededBy;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** The credential's status on {@code onDate}, with expiry judged here rather than by the feed. */
    public @NonNull CredentialStatus statusOn(@NonNull LocalDate onDate) {
        return CredentialStatus.effective(status, expiresOn, onDate);
    }

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
