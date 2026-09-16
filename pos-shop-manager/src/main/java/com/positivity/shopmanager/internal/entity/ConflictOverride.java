package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A manager's recorded acceptance of one SOFT {@link SchedulingConflict} (DECISION-SHOPMGMT-002 and
 * -007, CAP-326). Immutable: there is no {@code updated_at}, and {@link
 * com.positivity.shopmanager.internal.repository.ConflictOverrideRepository} exposes no update or
 * delete. One override per conflict — the schema's {@code (tenant_id, conflict_id)} key — so a
 * second attempt is refused, never layered.
 *
 * <p>Approval is single-actor (spec D18.3): {@link #approvedBy} is {@link #overriddenBy} and {@link
 * #approvedAt} is {@link #createdAt}, which is what lets DECISION-002's second audit query (SOFT
 * overrides without approval; should be zero) hold without a two-step flow. A HARD conflict never
 * gets one of these rows; the service refuses before writing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conflict_override")
public class ConflictOverride extends TenantScopedEntity {

    @Id
    @UUIDv7Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    @JoinColumn(name = "conflict_id", nullable = false, updatable = false)
    private SchedulingConflict conflict;

    @Column(name = "overridden_by", nullable = false, length = 255, updatable = false)
    private String overriddenBy;

    @Column(name = "override_reason", nullable = false, length = 2000, updatable = false)
    private String overrideReason;

    @Column(name = "approved_by", nullable = false, length = 255, updatable = false)
    private String approvedBy;

    @Column(name = "approved_at", nullable = false, updatable = false)
    private Instant approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
