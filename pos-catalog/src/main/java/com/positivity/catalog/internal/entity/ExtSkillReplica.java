package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only replica of the People domain's skill registry, fed by {@code people.skill.updated}
 * (CAP-329, ADR-0044 §6). A service's skill requirement is validated against this table; only
 * {@link com.positivity.catalog.internal.service.PeopleEventsListener} writes it.
 *
 * <p>Global like its source (CAP-328): an ASE certification means the same thing in every shop,
 * so there is one vocabulary, not one per tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@TenantGlobal(
        reason = "replica of the @TenantGlobal skill registry owned by pos-people; one vocabulary for every tenant"
                + " (db/tenancy-global-tables.txt)")
@Table(name = "ext_skill")
public class ExtSkillReplica {

    @Id
    @AssignedIdentifier("the owner's UUIDv7 registry id, stored verbatim by the replica consumer")
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "competence_code", nullable = false, length = 64)
    private String competenceCode;

    @Column(name = "min_gvwr_class", nullable = false)
    private int minGvwrClass;

    @Column(name = "max_gvwr_class", nullable = false)
    private int maxGvwrClass;

    /** False is a retirement: a requirement may still name the skill and must be told so. */
    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the owner's UUIDv7, stored verbatim. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
