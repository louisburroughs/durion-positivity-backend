package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
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
 * Read-only credential replica fed by {@code people.events.v1}
 * ({@code people.person-credential.updated}, ADR-0044 §6, #2122). pos-people owns the aggregate;
 * only {@link com.positivity.workorder.internal.service.PeopleReplicaEventsListener} writes this
 * table. It exists so the dispatch board can judge a workorder's required certifications against
 * what each mechanic actually holds.
 *
 * <p>{@link #status} is stored as received, but expiry is judged on read via {@link #isHeldOn}
 * against the date being asked about — the feed's view of "today" is not the facility's.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_person_credential")
public class ExtPersonCredentialReplica extends TenantScopedEntity {

    /** The feed status of a credential that is in force; REVOKED and SUPERSEDED are the owner's decisions. */
    public static final String STATUS_ACTIVE = "ACTIVE";

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

    /** The competence the skill certifies (e.g. {@code BRAKES}). */
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

    @Column(name = "source_credential_code", length = 128)
    private String sourceCredentialCode;

    @Column(name = "issued_on", nullable = false)
    private LocalDate issuedOn;

    /** Null means the credential never expires — not that it has. */
    @Column(name = "expires_on")
    private LocalDate expiresOn;

    @Column(name = "proficiency")
    private Integer proficiency;

    /** Status as received; read through {@link #isHeldOn(LocalDate)}. */
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

    /**
     * Whether the person holds this credential on {@code onDate}: the feed says ACTIVE, and it has
     * not expired by then (a null {@code expiresOn} never expires; the expiry day itself still counts).
     */
    public boolean isHeldOn(@NonNull LocalDate onDate) {
        return STATUS_ACTIVE.equals(status) && (expiresOn == null || !expiresOn.isBefore(onDate));
    }

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
