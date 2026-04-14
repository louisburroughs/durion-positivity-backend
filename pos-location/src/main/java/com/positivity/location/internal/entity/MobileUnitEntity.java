package com.positivity.location.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Mobile unit aggregate for field-service dispatch.
 *
 * Issue: #76
 */
@Entity
@Table(
        name = "mobile_units",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uq_mobile_units_base_location_name",
                    columnNames = {"base_location_id", "name"})
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class MobileUnitEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "base_location_id")
    private Location baseLocation;

    public UUID getBaseLocationId() {
        return baseLocation != null ? baseLocation.getId() : null;
    }

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "travel_buffer_policy_id", columnDefinition = "UUID")
    private UUID travelBufferPolicyId;

    private String notes;

    @Builder.Default
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "mobile_unit_capabilities", joinColumns = @JoinColumn(name = "mobile_unit_id"))
    @Column(name = "service_capability_id", columnDefinition = "UUID")
    private Set<UUID> capabilityIds = new HashSet<>();

    @CreatedDate
    @Column(name = "created_at")
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "created_by", columnDefinition = "UUID")
    private UUID createdBy;

    @Column(name = "updated_by", columnDefinition = "UUID")
    private UUID updatedBy;
}
