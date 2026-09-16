package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.shopmanager.internal.enums.ConflictSeverity;
import com.positivity.tenancy.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One rule that fired against one booking attempt (DECISION-SHOPMGMT-002, CAP-326).
 *
 * <p>{@link #appointment} is null for a refused attempt: a HARD conflict means nothing was booked,
 * and this row is the record that it was refused and why. For a SOFT conflict the booking proceeds
 * (spec D10: warn, allow) and the row points at the appointment it was accepted under, ready for a
 * manager's {@link ConflictOverride}. {@link #severity} is copied from the rule at detection so the
 * record's audit query — HARD conflicts with overrides, which should be zero — needs no join.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "scheduling_conflict")
public class SchedulingConflict extends TenantScopedEntity {

    @Id
    @UUIDv7Id
    @GeneratedValue
    @Column(name = "id", columnDefinition = "UUID")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id")
    private Appointment appointment;

    @ManyToOne(optional = false, fetch = FetchType.EAGER)
    @JoinColumn(name = "conflict_rule_id", nullable = false)
    private ConflictRule conflictRule;

    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 8)
    private ConflictSeverity severity;

    @Column(name = "location_id", nullable = false, columnDefinition = "UUID")
    private UUID locationId;

    /** The contended resource as the appointment names it — a bay id or mechanic id as text. */
    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Column(name = "attempted_start_at", nullable = false)
    private Instant attemptedStartAt;

    @Column(name = "attempted_end_at", nullable = false)
    private Instant attemptedEndAt;

    /** The rule's template rendered for this attempt; what the 409 envelope's {@code message} shows. */
    @Column(name = "detail", columnDefinition = "TEXT")
    private String detail;

    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;
}
