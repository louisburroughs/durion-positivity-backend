package com.positivity.location.internal.entity;

import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
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

/**
 * Storage location aggregate for floor/shelf/bin/cage/truck topology.
 *
 * Issue: CAP-214 #39
 */
@Entity
@Table(name = "storage_location")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class StorageLocationEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(unique = false)
    private String barcode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private StorageLocationType type;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    @Column(nullable = false, length = 20)
    private StorageLocationStatus status = StorageLocationStatus.ACTIVE;

    @Column(name = "site_id", nullable = false, columnDefinition = "UUID")
    private UUID siteId;

    @Column(name = "parent_storage_location_id", columnDefinition = "UUID")
    private UUID parentStorageLocationId;

    @Column(name = "capacity", columnDefinition = "TEXT")
    private String capacity;

    @Column(name = "temperature", columnDefinition = "TEXT")
    private String temperature;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (status == null) {
            status = StorageLocationStatus.ACTIVE;
        }
    }
}
