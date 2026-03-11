package com.positivity.shopmanager.internal.entity;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.positivity.shared.id.UUIDv7Id;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@EntityListeners(AuditingEntityListener.class)
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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false)
    private Appointment appointment;

    public UUID getAppointmentId() {
        return appointment != null ? appointment.getAppointmentId() : null;
    }

    @Column(name = "created_at", nullable = false, updatable = false)
    @CreatedDate
    private Instant createdAt;

    @Column(name = "status", length = 50)
    private String status;

}
