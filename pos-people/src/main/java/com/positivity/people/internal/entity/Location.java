package com.positivity.people.internal.entity;

import com.positivity.people.internal.enums.LocationType;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "location")
@Getter
@Setter
public class Location {

    @Id
    @Column(name = "location_id", columnDefinition = "UUID", updatable = false, nullable = false)
    private UUID locationId;

    @Column(name = "code", nullable = false, unique = true, length = 100)
    private String code;

    @Column(name = "display_name", nullable = false, length = 255)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "location_type", nullable = false, length = 30)
    private LocationType locationType;

    @Column(name = "address", length = 1000)
    private String address;

    @Column(name = "timezone", nullable = false, length = 100)
    private String timezone;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "manager_id", columnDefinition = "UUID")
    private UUID managerId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (locationId == null) {
            locationId = UUIDv7Generator.generate();
        }
    }
}
