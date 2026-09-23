package com.positivity.people.internal.entity;

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
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One row of a tenant's job-role list (durion#2157): HR master data such as "Lead Technician" or
 * "Parts Counter" that a tenant defines for its own employees. Unlike {@link Skill} -- reference
 * data a national body certifies, and therefore platform-global -- a job role is a label the
 * tenant invents for itself, so this entity is tenant-scoped ({@link TenantScopedEntity}), not
 * {@code @TenantGlobal}. See {@code V6__job_role.sql}'s header for the full reasoning.
 *
 * <p>It is HR master data, not an application role: it carries no permissions and must never
 * participate in permission evaluation. Permission-bearing roles live in pos-security-service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "job_role")
public class JobRole extends TenantScopedEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    /** The tenant's own short code, e.g. {@code LEAD_TECH}; unique within the tenant. */
    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description", length = 1000)
    private String description;

    @Builder.Default
    @Column(name = "active", nullable = false)
    private boolean active = true;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
