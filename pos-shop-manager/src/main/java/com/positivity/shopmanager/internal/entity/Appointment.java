package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shared.id.UUIDv7Id;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import com.positivity.shopmanager.internal.enums.CancellationReasonCode;

import jakarta.annotation.Generated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "appointment")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Appointment {

    @Id
    @UUIDv7Id
    @Generated(value = "com.positivity.shared.id.UUIDv7Generator")
    @Column(name = "appointment_id", columnDefinition = "UUID")
    private UUID appointmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AppointmentStatus status;

    @Column(name = "location_id", nullable = false, columnDefinition = "UUID")
    private UUID locationId;

    @Column(name = "resource_id", length = 128)
    private String resourceId;

    @Column(name = "resource_type", length = 64)
    private String resourceType;

    @Column(name = "crm_customer_id", nullable = false, columnDefinition = "UUID")
    private UUID crmCustomerId;

    @Column(name = "crm_vehicle_id", nullable = false, columnDefinition = "UUID")
    private UUID crmVehicleId;

    @Column(name = "customer_snapshot", columnDefinition = "TEXT")
    private String customerSnapshot;

    @Column(name = "vehicle_snapshot", columnDefinition = "TEXT")
    private String vehicleSnapshot;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "end_at", nullable = false)
    private Instant endAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "workorder_link_ref", length = 255)
    private String workorderLinkRef;

    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancellation_reason", length = 128)
    private CancellationReasonCode cancellationReasonCode;

    @Column(name = "cancellation_notes", length = 1000)
    private String cancellationNotes;

    /**
     * Set to {@code true} when this appointment was scheduled despite a detected
     * conflict.
     */
    @Builder.Default
    @Column(name = "is_conflict_override", nullable = false)
    private boolean isConflictOverride = false;

    @PrePersist
    public void generateAppointmentId() {
        if (appointmentId == null) {
            appointmentId = UUIDv7Generator.generate();
        }
    }
}
