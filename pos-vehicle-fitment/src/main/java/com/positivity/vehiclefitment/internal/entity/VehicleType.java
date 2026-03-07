package com.positivity.vehiclefitment.internal.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.UUID;

import com.positivity.shared.id.UUIDv7Id;

@Data
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "vehicle_type")
public class VehicleType {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "make_id")
    private Make make;

    private String vehicleTypeName;

    private String vehicleTypeId;

    private LocalDateTime cacheTimestamp;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

}
