package com.positivity.people.internal.entity;

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
 * One row of the skill registry (CAP-328, durion#485, spec D2/D8/D13): a competence and the GVWR
 * class range it certifies work on. Reference data — a skill is not the aggregate; the credential
 * a person holds is ({@code PersonCredential}). Platform-global: an ASE certification means the
 * same thing in every shop. Seeded by {@code R__seed_people_2_skill_registry.sql}; the application
 * never writes this table.
 *
 * <p>{@code minGvwrClass}..{@code maxGvwrClass} is the range rather than a LIGHT/HEAVY token so the
 * A-series/T-series line (class 4, where ASE's truck tests begin) is one row of data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "skill")
@TenantGlobal(
        reason = "skill registry reference data shared by every tenant (spec D2, ADR-0062 section 5,"
                + " db/tenancy-global-tables.txt)")
public class Skill {

    @Id
    @AssignedIdentifier("seeded reference data keyed by md5(code); the seed, not a generator, decides the id")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** Durion's code — {@code BRAKES-LIGHT}, {@code BRAKES-MEDIUM_HEAVY}; vendor codes map onto it. */
    @Column(name = "code", nullable = false, length = 64, unique = true)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    /** The competence regardless of duty class — {@code BRAKES} for both brake rows. */
    @Column(name = "competence_code", nullable = false, length = 64)
    private String competenceCode;

    @Column(name = "min_gvwr_class", nullable = false)
    private int minGvwrClass;

    @Column(name = "max_gvwr_class", nullable = false)
    private int maxGvwrClass;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Whether the skill certifies work on a vehicle of this FHWA GVWR class (1..8). */
    public boolean appliesToGvwrClass(int gvwrClass) {
        return gvwrClass >= minGvwrClass && gvwrClass <= maxGvwrClass;
    }

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the id is seed-assigned, so no generator is attached. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
