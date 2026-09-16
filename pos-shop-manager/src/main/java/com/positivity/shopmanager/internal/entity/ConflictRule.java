package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.shopmanager.internal.enums.ConflictResourceType;
import com.positivity.shopmanager.internal.enums.ConflictSeverity;
import com.positivity.tenancy.TenantGlobal;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One row of DECISION-SHOPMGMT-002's rule catalog (CAP-326, durion#483): a code, its severity and
 * the kind of resource it constrains.
 *
 * <p><strong>The code is the API reason code.</strong> A {@code conflict_rule.code} and the string a
 * 409 envelope or an opening search returns for it are the same value (spec D10.1), so there is no
 * mapping table for the two to drift between. That is also why the table is platform-global (spec
 * D18.2): one namespace for every tenant, and no tenant able to switch off {@code BAY_DOUBLE_BOOKED}
 * while the exclusion constraint keeps enforcing it. Rows are seeded by {@code
 * R__seed_shop_manager_1_conflict_rules.sql}; nothing in the application writes this table.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conflict_rule")
@TenantGlobal(
        reason = "DECISION-SHOPMGMT-002 rule catalog: the code is the API reason code, one namespace for every"
                + " tenant, and severity must not be tenant-editable under a constraint that still enforces it")
public class ConflictRule {

    @Id
    @AssignedIdentifier("seeded reference data keyed by md5(code); the seed, not a generator, decides the id")
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    /** The rule's code and the API's reason code — the same string, by design. */
    @Column(name = "code", nullable = false, length = 50, unique = true)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 8)
    private ConflictSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(name = "resource_type", nullable = false, length = 16)
    private ConflictResourceType resourceType;

    /** Human-readable template with {@code {placeholders}} the enforcement tier renders. */
    @Column(name = "message_template", nullable = false, columnDefinition = "TEXT")
    private String messageTemplate;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    /**
     * Hook for this module's ArchUnit rule that every entity depends on {@link UUIDv7Id} (ADR-0013),
     * as the replicas do. The id itself is seed-assigned ({@code @AssignedIdentifier}), so no
     * generator is attached; the platform-wide rule reads that annotation, this one reads the type.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
