package com.positivity.shopmanager.internal.entity;

import java.time.Instant;
import java.util.UUID;

import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "work_order_appointment_mapping")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkOrderAppointmentMapping {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "work_order_id", nullable = false, columnDefinition = "UUID")
    private UUID workOrderId;

    @Column(name = "appointment_id", nullable = false, columnDefinition = "UUID")
    private UUID appointmentId;

    @Builder.Default
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "status", length = 50)
    private String status;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
