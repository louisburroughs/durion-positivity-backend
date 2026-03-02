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

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "appointment_service_request")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentServiceRequest {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "service_request_id", nullable = false, columnDefinition = "UUID")
    private UUID serviceRequestId;

    @Column(name = "appointment_id", nullable = false, columnDefinition = "UUID")
    private UUID appointmentId;

    @Column(name = "service_entity_id", nullable = false, columnDefinition = "UUID")
    private UUID serviceEntityId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
