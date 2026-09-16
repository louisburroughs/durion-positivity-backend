package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Header of a service's requirement profile (CAP-329, spec §4.1). Its existence is the fact:
 * absent means the service's requirements are <em>not configured</em>; present with no
 * children means <em>unconstrained</em>. The two must never collapse into one another, which
 * is why declaring an empty requirement set still writes this row.
 *
 * <p>Keyed 1:1 by the service id rather than a generated key — one profile per service, and
 * the consumer's replica keys the same way.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "service_requirement_profile")
public class ServiceRequirementProfileEntity extends TenantScopedEntity {

    @Id
    @AssignedIdentifier("1:1 with service: the profile is keyed by the service's own UUIDv7 id")
    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    /** When the requirements were last declared; the consumer's "configured" signal. */
    @Column(name = "configured_at", nullable = false)
    private Instant configuredAt;

    @Column(name = "configured_by")
    private String configuredBy;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** ArchUnit UUIDv7 rule hook (ADR-0013): the key is the service's UUIDv7. */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
