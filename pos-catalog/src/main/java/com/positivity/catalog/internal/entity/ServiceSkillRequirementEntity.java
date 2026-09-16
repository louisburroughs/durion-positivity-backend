package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
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
 * One skill a service requires, conditioned on the vehicle's GVWR class (CAP-329, spec §4.5,
 * D8/D13). Both class bounds null means ANY: the requirement holds for every vehicle, a
 * class-less one included. A range means the requirement applies only when the vehicle's class
 * falls inside it — how one unforked service requires A-series brakes on a class 1–3 vehicle and
 * T-series on a class 4–8 one.
 *
 * <p>Looked up by {@code serviceId}, no JPA relation to the profile or the service (the module's
 * convention for service children, see {@link ServiceLaborStandardEntity}). No proficiency
 * threshold (D7).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "service_skill_requirement")
public class ServiceSkillRequirementEntity extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    /** Registry skill id, validated against the {@code ext_skill} replica on write. */
    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "min_gvwr_class")
    private Integer minGvwrClass;

    @Column(name = "max_gvwr_class")
    private Integer maxGvwrClass;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** True when the requirement is unconditioned (ANY class). */
    public boolean appliesToAnyClass() {
        return minGvwrClass == null && maxGvwrClass == null;
    }

    /**
     * Whether the requirement applies to a vehicle of {@code gvwrClass}; {@code null} (class not
     * determined) satisfies only an ANY-class requirement.
     */
    public boolean appliesTo(@NonNull Integer gvwrClass) {
        return appliesToAnyClass() || (gvwrClass >= minGvwrClass && gvwrClass <= maxGvwrClass);
    }
}
