package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Immutable audit record capturing the details of a scheduling conflict override. */
@Entity
@Table(name = "override_record")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OverrideRecord {

    @Id
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

    @PrePersist
    public void generateId() {
        if (overrideId == null) {
            overrideId = UUIDv7Generator.generate();
        }
    }
}
