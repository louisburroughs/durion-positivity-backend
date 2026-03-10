package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.shopmanager.internal.enums.ShopAuditEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable audit record for schedule and assignment changes.
 * Story #61 — once written, this entity may never be modified or deleted.
 */
@Entity
@Immutable
@EntityListeners(AuditingEntityListener.class)
@Table(name = "shop_audit_entry")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShopAuditEntry {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "id", columnDefinition = "UUID", updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private ShopAuditEventType eventType;

    /** Workorder identifier (the entity being changed). */
    @Column(name = "workorder_id", length = 128, updatable = false)
    private String workorderId;

    @Column(name = "appointment_id", length = 128, updatable = false)
    private String appointmentId;

    @Column(name = "mechanic_id", length = 128, updatable = false)
    private String mechanicId;

    @Column(name = "location_id", length = 128, updatable = false)
    private String locationId;

    /**
     * Identity of the actor who triggered the change.
     * MUST be sourced from the security context (ADR-0018); never caller-supplied.
     */
    @Column(name = "actor_user_id", nullable = false, length = 255, updatable = false)
    private String actorUserId;

    @Column(name = "change_summary_text", length = 1024, updatable = false)
    private String changeSummaryText;

    /** JSON Patch (RFC 6902) structured diff. */
    @Column(name = "change_patch", columnDefinition = "TEXT", updatable = false)
    private String changePatch;

    @Column(name = "reason_code", length = 128, updatable = false)
    private String reasonCode;

    @Column(name = "reason_notes", length = 2048, updatable = false)
    private String reasonNotes;

    /**
     * Retention period in years. Default is 7 (Story #61 / RQ3).
     */
    @Column(name = "retention_years", nullable = false, updatable = false)
    @Builder.Default
    private int retentionYears = 7;

    @CreatedDate
    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;
}
