package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * One skill a replicated catalog service requires (CAP-329), replace-set per fact. Both class
 * bounds null means the requirement applies to every vehicle (ANY); a range means only to a
 * vehicle whose GVWR class falls inside it — how one brake service needs A-series competence on a
 * class 1–3 vehicle and T-series on a class 4–8 one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_catalog_service_skill")
public class ExtCatalogServiceSkillReplica extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "skill_id", nullable = false)
    private UUID skillId;

    @Column(name = "skill_code", nullable = false, length = 64)
    private String skillCode;

    @Column(name = "min_gvwr_class")
    private Integer minGvwrClass;

    @Column(name = "max_gvwr_class")
    private Integer maxGvwrClass;

    /**
     * Whether this requirement applies to a vehicle of {@code gvwrClass}. A null class (the vehicle's
     * class is not determined) satisfies only an ANY-class requirement.
     */
    public boolean appliesTo(@Nullable Integer gvwrClass) {
        if (minGvwrClass == null && maxGvwrClass == null) {
            return true;
        }
        return gvwrClass != null && gvwrClass >= minGvwrClass && gvwrClass <= maxGvwrClass;
    }
}
