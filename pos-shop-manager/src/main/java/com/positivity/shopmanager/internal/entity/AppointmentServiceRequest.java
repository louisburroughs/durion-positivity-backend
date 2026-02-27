package com.positivity.shopmanager.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "appointment_service_request")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AppointmentServiceRequest {

    @Id
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

    @PrePersist
    public void generateServiceRequestId() {
        if (serviceRequestId == null) {
            serviceRequestId = UUIDv7Generator.generate();
        }
    }
}
