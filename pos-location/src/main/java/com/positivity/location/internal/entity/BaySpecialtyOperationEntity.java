package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * The specialty map (CAP-325 D14): which catalog operation codes a {@link
 * com.positivity.location.internal.enums.BayType} is the <em>only</em> type able to perform.
 *
 * <p>This is the whole of bay-eligibility configuration. Everything not in this map is general
 * work, performed in a {@code GENERAL_SERVICE} bay, and general bays declare nothing. So the map
 * asserts only what is knowable — the alignment rack is in the alignment bay — and defines
 * "general" as the rest rather than enumerating it. A new catalog service is general unless
 * someone adds a row here, which is why the common case needs no bay edit.
 *
 * <p>One row per (bay type, operation code). Tenant-scoped because a tenant's shops may confine
 * work differently; the {@code R__seed_location_2_bay_specialty.sql} seed supplies the platform
 * default. {@code operation_code} is a catalog code validated against {@code ext_catalog_service}
 * on write and stored uppercased; it is deliberately not a foreign key, because the vocabulary is
 * owned by another module (ADR-0044 §6).
 *
 * <p>The per-bay {@code BayEntity.serviceCapabilityCodes} is what consumers read from the fact;
 * this map is what a bay of a given type is <em>defaulted</em> to when created without an explicit
 * list. A bay may carry its own list where a shop's equipment differs from the type's default.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "bay_specialty_operation",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_bay_specialty_operation",
                        columnNames = {"bay_type", "operation_code"}))
public class BaySpecialtyOperationEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    /** A {@code BayType} name. Stored as text, matching {@code bays.bay_type}. */
    @Column(name = "bay_type", nullable = false, length = 50)
    private String bayType;

    /** A catalog {@code operationCode}, {@code UPPER-DASH} per ADR-0059 §3. */
    @Column(name = "operation_code", nullable = false, length = 64)
    private String operationCode;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void normalize() {
        if (bayType != null) {
            bayType = bayType.trim().toUpperCase(Locale.ROOT);
        }
        if (operationCode != null) {
            operationCode = operationCode.trim().toUpperCase(Locale.ROOT);
        }
    }
}
