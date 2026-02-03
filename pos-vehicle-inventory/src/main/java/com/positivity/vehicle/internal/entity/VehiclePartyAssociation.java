package com.positivity.vehicle.internal.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Vehicle-party association entity for CAP:091 Story #104.
 * Manages temporal relationships between vehicles and parties (owners/drivers).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "vehicle_party_associations", indexes = {
        @Index(name = "idx_vpa_vehicle_id", columnList = "vehicle_id"),
        @Index(name = "idx_vpa_party_id", columnList = "party_id"),
        @Index(name = "idx_vpa_type_dates", columnList = "vehicle_id, association_type, effective_start_date, effective_end_date")
})
@EntityListeners(AuditingEntityListener.class)
public class VehiclePartyAssociation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "association_id", updatable = false, nullable = false)
    private UUID associationId;

    @NonNull
    @Column(name = "vehicle_id", nullable = false)
    private UUID vehicleId;

    @NonNull
    @Column(name = "party_id", nullable = false)
    private UUID partyId;

    @NonNull
    @Enumerated(EnumType.STRING)
    @Column(name = "association_type", nullable = false, length = 50)
    private AssociationType associationType;

    @NonNull
    @Column(name = "effective_start_date", nullable = false)
    private Instant effectiveStartDate;

    @Column(name = "effective_end_date")
    private Instant effectiveEndDate;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    public enum AssociationType {
        OWNER,
        PRIMARY_DRIVER
    }
}
