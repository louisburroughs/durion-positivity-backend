package com.positivity.shopmanager.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Audit record capturing the details of a scheduling conflict override. */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "override_record")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OverrideRecord {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "override_id", columnDefinition = "UUID")
    private UUID overrideId;

    @Column(name = "appointment_id", nullable = false, columnDefinition = "UUID")
    private UUID appointmentId;

    @Column(name = "overridden_by_user_id", nullable = false, length = 255)
    private String overriddenByUserId;

    @Column(name = "override_timestamp", nullable = false)
    private Instant overrideTimestamp;

    @Column(name = "override_reason", nullable = false, length = 2000)
    private String overrideReason;

    @Column(name = "conflict_details", columnDefinition = "TEXT")
    private String conflictDetails;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
