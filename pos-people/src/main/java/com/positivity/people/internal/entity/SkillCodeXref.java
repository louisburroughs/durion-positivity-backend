package com.positivity.people.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A vendor's credential code cross-referenced onto a registry {@link Skill} (CAP-328, spec D8;
 * ADR-0059 §3, precedent {@code service_operation_xref}). {@code source_code} names the vendor —
 * {@code ASE} — and {@code source_skill_code} its code as HR spells it, {@code A5-BRAKES}. Vendor
 * codes map onto Durion codes, never the reverse, so HR keeps sending ASE codes forever and an
 * unknown one fails the ingest instead of becoming a skill nobody holds.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "skill_code_xref")
@TenantGlobal(
        reason = "national credential codes cross-referenced onto the global skill registry (ADR-0059 section 3,"
                + " db/tenancy-global-tables.txt)")
public class SkillCodeXref {

    @Id
    @AssignedIdentifier("seeded reference data keyed by md5(source, code); the seed, not a generator, decides the id")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "skill_id", nullable = false)
    private Skill skill;

    @Column(name = "source_code", nullable = false, length = 32)
    private String sourceCode;

    @Column(name = "source_skill_code", nullable = false, length = 128)
    private String sourceSkillCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the id is seed-assigned, so no generator is attached. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
